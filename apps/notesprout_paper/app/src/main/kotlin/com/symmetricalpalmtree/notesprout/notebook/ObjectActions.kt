package com.symmetricalpalmtree.notesprout.notebook

import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.gpaper.core.model.Bounds
import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.notesprout.R
import com.symmetricalpalmtree.notesprout.core.Dialogs
import com.symmetricalpalmtree.notesprout.core.Slog
import com.symmetricalpalmtree.notesprout.extension.CapabilityRequiredException
import com.symmetricalpalmtree.notesprout.extension.CreatedObject
import com.symmetricalpalmtree.notesprout.extension.EditSpec
import com.symmetricalpalmtree.notesprout.extension.ExtensionCallException
import com.symmetricalpalmtree.notesprout.extension.ExtensionContract
import com.symmetricalpalmtree.notesprout.extension.InkTooLargeException
import com.symmetricalpalmtree.notesprout.extension.ObjectProviderClient
import com.symmetricalpalmtree.notesprout.extension.RecognizerClient
import com.symmetricalpalmtree.notesprout.extension.RecognizerNotReadyException
import com.symmetricalpalmtree.notesprout.extension.RecognizerReadiness
import com.symmetricalpalmtree.notesprout.extension.Requires
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The provider-facing half of a selection-toolbar action or an object edit (arc 4 / H4): the
 * `requires` guards, the recognizer consent flow ([RecognizerReadiness]), the "Recognizing…" popup,
 * the `createFromInk` / `applyAction` / `describeEdit` / `applyEdit` calls through
 * [ObjectProviderClient], and **every failure dialog** (all core-owned; the extension shows nothing).
 * What comes back — a [CreatedObject] for the selected ink, or a new payload for an object — goes to
 * the [Listener], which is the screen: it puts the result on the page (rows, undo, render, select).
 * Nothing here touches the paper or the stores, and no payload or recognized text is ever logged.
 *
 * One flow at a time ([busy]): a second tap while a create / apply / edit is under way is ignored.
 * Guards (INK leaf): `Requires.RECOGNIZER` with no recognizer installed → "needs a handwriting
 * recognizer" (first — nothing can be created without it, phase-start Q3); `Requires.MARKDOWN` with no
 * Markdown renderer → "needs the NSE · Markdown extension"; the page's object cap → "page full".
 * An OBJECT leaf guards `MARKDOWN` only (its result must render; the recognizer is not involved);
 * an edit guards nothing (the payload may change while the box waits for Markdown to return).
 * Failed / empty recognition leaves the ink untouched ("Couldn't read the handwriting"); a provider
 * that fails or times out → "The <label> extension didn't respond".
 */
class ObjectActions(
    private val activity: AppCompatActivity,
    private val providers: () -> ObjectProviders,
    private val listener: Listener,
) {
    interface Listener {
        /** The provider made an object of the selected [strokes]: create it at [bounds]' top-left, consume the
         *  ink, render, select — one undo step. Returns once the object is on the page (the popup waits). */
        suspend fun onCreated(providerKey: String, created: CreatedObject, strokes: List<Stroke>, bounds: Bounds)
        /** [obj]'s payload became [payload] (an action or an edit): persist, undo step, re-render, re-select. */
        suspend fun onPayloadChanged(obj: PageObject, payload: String)
    }

    private var busy = false
    private var lastWarmMs = 0L

    /**
     * Warm-up cue (H5, user's design): the H sub-toolbar just opened, so a leaf that needs the
     * recognizer is likely next — bind the recognizer once for a `status()` so its process starts
     * (and, on the ML Kit side, primes its engine) while the user is still choosing H1–H6. Throttled
     * to one bind per [WARM_INTERVAL_MS]; nothing crosses but the call itself, nothing is stored, no
     * dialog on failure. Only for actions that declare `Requires.RECOGNIZER` with a recognizer installed.
     */
    fun warm(action: ToolbarAction) {
        if (action.requires and Requires.RECOGNIZER == 0) return
        val ref = providers().recognizerRef ?: return
        val now = System.currentTimeMillis()
        if (now - lastWarmMs < WARM_INTERVAL_MS) return
        lastWarmMs = now
        activity.lifecycleScope.launch {
            try {
                val status = RecognizerClient(activity, ref).status()
                Slog.d(TAG) { "warm-up: recognizer status $status" }
            } catch (e: ExtensionCallException) {
                Slog.d(TAG) { "warm-up failed: ${e.message}" }
            }
        }
    }

    /**
     * A leaf action tapped on the toolbar. [strokes] = the selection's strokes (INK shape when
     * [obj] is null); [obj] = the one selected object (OBJECT shape); [objectCount] = the page's live
     * objects (cap check).
     */
    fun perform(providerKey: String, action: ToolbarAction, strokes: List<Stroke>, bounds: Bounds, obj: PageObject?, objectCount: Int) {
        if (busy || action.isParent) return
        val p = providers()
        val client = p.clientFor(activity, providerKey) ?: return
        if (obj != null) {
            if (action.requires and Requires.MARKDOWN != 0 && p.markdownRef == null) { problem(R.string.objects_needs_markdown); return }
            val (_, typeId) = ExtensionContract.parseIdentity(obj.providerIdentity) ?: return
            busy = true
            activity.lifecycleScope.launch {
                try {
                    val payload = client.applyAction(action.id, typeId, obj.payload)
                    Slog.d(TAG) { "applyAction ${action.id}: ${if (payload == null) "no change" else "${payload.length} chars"}" }
                    if (payload != null && payload != obj.payload) listener.onPayloadChanged(obj, payload)
                } catch (e: ExtensionCallException) {
                    failed(providerKey, "applyAction", e)
                } finally {
                    busy = false
                }
            }
            return
        }
        if (strokes.isEmpty()) return
        if (action.requires and Requires.RECOGNIZER != 0 && p.recognizerRef == null) { problem(R.string.objects_needs_recognizer); return }
        if (action.requires and Requires.MARKDOWN != 0 && p.markdownRef == null) { problem(R.string.objects_needs_markdown); return }
        if (objectCount >= ExtensionContract.MAX_OBJECTS_PER_PAGE) { problem(R.string.objects_page_full); return }
        busy = true
        val create: suspend () -> Unit = { try { create(providerKey, client, action, strokes, bounds) } finally { busy = false } }
        val recognizer = p.recognizerRef
        if (action.requires and Requires.RECOGNIZER != 0 && recognizer != null) {
            // The model-consent flow first (READY → at once; NEEDS_DOWNLOAD → dialog → download → continue).
            RecognizerReadiness.ensureReady(
                activity, RecognizerClient(activity, recognizer),
                onReady = create, onGaveUp = { busy = false }, problemTitleRes = R.string.objects_problem_title,
            )
        } else {
            activity.lifecycleScope.launch { create() }
        }
    }

    /** READY path: "Recognizing…" popup for the whole call, then the listener puts the object on the page. */
    private suspend fun create(providerKey: String, client: ObjectProviderClient, action: ToolbarAction, strokes: List<Stroke>, bounds: Bounds) {
        if (activity.isFinishing || activity.isDestroyed) return
        val popup = Dialogs.style(AlertDialog.Builder(activity).setMessage(R.string.recognize_running).setCancelable(false).create())
        popup.show()
        try {
            val ink = withContext(Dispatchers.Default) { InkPayload.fromStrokes(strokes) }
            val t0 = System.currentTimeMillis()
            val created = client.createFromInk(action.id, ink, bounds.width, bounds.height)
            Slog.d(TAG) { "createFromInk ${action.id}: ${ink.size} strokes → ${if (created == null) "nothing" else "type ${created.typeId}"} in ${System.currentTimeMillis() - t0} ms" }
            if (created == null) {
                popup.dismiss()
                Dialogs.problem(activity, R.string.objects_unreadable_title, R.string.objects_unreadable_body)
                return
            }
            listener.onCreated(providerKey, created, strokes, bounds)
        } catch (e: InkTooLargeException) {
            problem(R.string.recognize_too_dense)
        } catch (e: RecognizerNotReadyException) {
            problem(R.string.recognize_still_downloading)
        } catch (e: ExtensionCallException) {
            failed(providerKey, "createFromInk", e)
        } finally {
            if (popup.isShowing) popup.dismiss()
        }
    }

    /**
     * A tap on the one selected object: `describeEdit` → null → nothing · spec → [present] (the screen
     * shows [ObjectEditDialog] pen-idle) → Save → `applyEdit` → a payload → the listener.
     */
    fun editTapped(providerKey: String, obj: PageObject, present: (EditSpec, onSave: (String) -> Unit) -> Unit) {
        if (busy) return
        val client = providers().clientFor(activity, providerKey) ?: return
        val (_, typeId) = ExtensionContract.parseIdentity(obj.providerIdentity) ?: return
        activity.lifecycleScope.launch {
            val spec = try {
                client.describeEdit(typeId, obj.payload)
            } catch (e: ExtensionCallException) {
                failed(providerKey, "describeEdit", e); null
            } ?: return@launch
            present(spec) { text ->
                if (busy) return@present
                busy = true
                activity.lifecycleScope.launch {
                    try {
                        val payload = client.applyEdit(typeId, obj.payload, text)
                        Slog.d(TAG) { "applyEdit: ${if (payload == null) "no change" else "${payload.length} chars"}" }
                        if (payload != null && payload != obj.payload) listener.onPayloadChanged(obj, payload)
                    } catch (e: ExtensionCallException) {
                        failed(providerKey, "applyEdit", e)
                    } finally {
                        busy = false
                    }
                }
            }
        }
    }

    private fun failed(providerKey: String, what: String, e: ExtensionCallException) {
        Slog.d(TAG) { "$what failed: ${e.javaClass.simpleName}: ${e.message}" }
        when {
            e is CapabilityRequiredException && e.requires == Requires.RECOGNIZER -> problem(R.string.objects_needs_recognizer)
            e is CapabilityRequiredException -> problem(R.string.objects_needs_markdown)
            else -> Dialogs.problem(activity, activity.getString(R.string.objects_problem_title),
                activity.getString(R.string.objects_provider_failed, providers().labelOf(providerKey)))
        }
    }

    private fun problem(messageRes: Int) = Dialogs.problem(activity, R.string.objects_problem_title, messageRes)

    private companion object {
        /** Warm-up binds at most this often (a bind pair per H tap would be waste). */
        private const val WARM_INTERVAL_MS = 20_000L
        const val TAG = "ObjectActions"
    }
}
