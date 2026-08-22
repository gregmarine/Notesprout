package com.symmetricalpalmtree.notesproutsn.notebook

import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.notesproutsn.R
import com.symmetricalpalmtree.notesproutsn.core.Dialogs
import com.symmetricalpalmtree.notesproutsn.core.RecognizingOverlay
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.extension.ExtensionCallException
import com.symmetricalpalmtree.notesproutsn.extension.ExtensionRegistry
import com.symmetricalpalmtree.notesproutsn.extension.InkStroke
import com.symmetricalpalmtree.notesproutsn.extension.InkTooLargeException
import com.symmetricalpalmtree.notesproutsn.extension.RecognizerClient
import com.symmetricalpalmtree.notesproutsn.extension.RecognizerNotReadyException
import com.symmetricalpalmtree.notesproutsn.extension.RecognizerReadiness
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference

/**
 * Ink → title: the convert half of the heading flow (N2). The user lassoes some handwriting, taps H,
 * taps a level; this takes the selected strokes out to the one trusted `HANDWRITING_RECOGNIZER`
 * extension and comes back with a **single-line** title — or with a reason, and nothing changed.
 *
 * It is `recognizeInk`, not `recognizePage`: a heading is one writing area, so there is nothing to
 * segment, and the empty `preContext` says the same thing. Recognition comes back with the
 * recognizer's own line breaks; a heading has exactly one line, so every run of whitespace collapses
 * to a single space and the result is trimmed. Blank after that is a *failure* the user must hear
 * about — the lassoed ink is left exactly as it was and no heading is created.
 *
 * The flow is the debug menu's, minus the debug: one flow at a time (a [WeakReference] guard, so a
 * flow torn down with its activity cannot leave the app wedged), the extension re-discovered at the
 * tap, [RecognizerReadiness] in front for consent and download, and the same exception → problem
 * dialog mapping. What is different is the waiting UI — a [RecognizingOverlay] box, not a dialog,
 * and it is always down before any dialog goes up.
 *
 * **Nothing recognized is ever logged** — counts and durations only. This object also touches no
 * store: it hands the caller a title and the caller decides what a heading is.
 */
object HeadingConvert {

    private const val TAG = "HeadingConvert"

    /** Every run of whitespace — newlines included — that a one-line title must not contain. */
    private val WHITESPACE = Regex("\\s+")

    /**
     * The one-flow-at-a-time guard, owned by the activity that started the flow: a flow whose dialog
     * is torn down with its activity fires no `onCancel`, so a plain process-lifetime boolean would
     * stick forever. Busy ⇔ the owner is still alive.
     */
    private var busyOwner: WeakReference<AppCompatActivity>? = null
    private fun busy(): Boolean = busyOwner?.get()?.let { !it.isFinishing && !it.isDestroyed } ?: false
    private fun claim(activity: AppCompatActivity) { busyOwner = WeakReference(activity) }
    private fun release() { busyOwner = null }

    /**
     * Recognize [strokes] as one heading title.
     *
     * [strokes] must already be in **writing order** — [InkPayload] hands the recognizer a sequence,
     * and a set's iteration order would scramble it. [areaWidth]/[areaHeight] are the **selection
     * box's** size in page px — the writing area is the box the ink was lassoed in, not the page.
     * ML Kit reads the area as the scale of the writing (its own page pipeline recognizes per line
     * with the line's box, and Paper's H action passes the selection bounds); a page-sized area
     * under a one-line title collapses recognition to fragments.
     *
     * Exactly one of [onRecognized] (a non-blank single-line title) and [onGaveUp] runs, on Main —
     * [onGaveUp] covering every path after which no heading will exist: no extension, consent
     * declined, download failed or cancelled, the call failed, or nothing was recognized.
     */
    fun run(
        activity: AppCompatActivity,
        strokes: List<Stroke>,
        areaWidth: Float,
        areaHeight: Float,
        onRecognized: (String) -> Unit,
        onGaveUp: () -> Unit = {},
    ) {
        // A second tap while a flow is up is *not* a give-up: firing the caller's teardown here
        // would pull the selection out from under the flow that is still running. It does nothing.
        if (busy()) return
        if (strokes.isEmpty()) { onGaveUp(); return }   // the caller guards; belt and braces
        claim(activity)
        activity.lifecycleScope.launch {
            val ref = ExtensionRegistry.handwritingRecognizer(activity)   // IO; re-discovered per tap
            if (activity.isFinishing || activity.isDestroyed) { release(); onGaveUp(); return@launch }
            if (ref == null) {
                Dialogs.problem(activity, R.string.recognize_problem_title, R.string.recognize_no_extension)
                release(); onGaveUp(); return@launch
            }
            val client = RecognizerClient(activity, ref)
            val ink = withContext(Dispatchers.Default) { InkPayload.fromStrokes(strokes) }
            if (ink.isEmpty()) {
                // Strokes that carry no points at all — nothing to recognize, and a tap that did
                // nothing has to say so.
                Dialogs.problem(activity, R.string.recognize_problem_title, R.string.heading_nothing_recognized)
                release(); onGaveUp(); return@launch
            }
            // The busy guard stays up for the whole flow and is released by whichever exit runs.
            RecognizerReadiness.ensureReady(
                activity, client,
                onReady = {
                    try { recognize(activity, client, ink, areaWidth, areaHeight, onRecognized, onGaveUp) }
                    finally { release() }
                },
                onGaveUp = { release(); onGaveUp() },
            )
        }
    }

    /** The READY path: the "Recognizing…" box, the call off Main, then a title or a problem dialog. */
    private suspend fun recognize(
        activity: AppCompatActivity,
        client: RecognizerClient,
        ink: List<InkStroke>,
        areaWidth: Float,
        areaHeight: Float,
        onRecognized: (String) -> Unit,
        onGaveUp: () -> Unit,
    ) {
        if (activity.isFinishing || activity.isDestroyed) { onGaveUp(); return }
        RecognizingOverlay.show(activity)
        try {
            val t0 = System.currentTimeMillis()
            // One writing area, no page context: the extension must not segment a heading into lines.
            val raw = client.recognizeInk(ink, areaWidth, areaHeight, "")
            val ms = System.currentTimeMillis() - t0
            val title = oneLine(raw)
            Slog.d(TAG) { "converted ${ink.size} strokes → ${title.length} chars in $ms ms" }
            // Down before anything else goes on screen — the box is never behind a dialog.
            RecognizingOverlay.hide(activity)
            if (activity.isFinishing || activity.isDestroyed) { onGaveUp(); return }
            if (title.isEmpty()) {
                // The ink is untouched: there is simply no heading to make out of it.
                Dialogs.problem(activity, R.string.recognize_problem_title, R.string.heading_nothing_recognized)
                onGaveUp()
            } else {
                onRecognized(title)
            }
        } catch (e: InkTooLargeException) {
            Slog.d(TAG) { "too dense: ${e.message}" }
            RecognizingOverlay.hide(activity)
            Dialogs.problem(activity, R.string.recognize_problem_title, R.string.recognize_too_dense)
            onGaveUp()
        } catch (e: RecognizerNotReadyException) {
            // READY was reported and then the extension lost it — a process restart mid-flow. Rare.
            Slog.d(TAG) { "not ready after READY: ${e.cause?.message}" }
            RecognizingOverlay.hide(activity)
            Dialogs.problem(activity, R.string.recognize_problem_title, R.string.recognize_still_downloading)
            onGaveUp()
        } catch (e: ExtensionCallException) {
            Slog.d(TAG) { "recognize failed: ${e.javaClass.simpleName}: ${e.message}" }
            RecognizingOverlay.hide(activity)
            Dialogs.problem(activity, R.string.recognize_problem_title, R.string.recognize_failed)
            onGaveUp()
        } finally {
            RecognizingOverlay.hide(activity)   // idempotent backstop for any path above
        }
    }

    /** A heading is one line: every run of whitespace becomes a single space, then trim. */
    private fun oneLine(text: String): String = text.replace(WHITESPACE, " ").trim()
}
