package com.symmetricalpalmtree.notesproutsn.extension

import android.content.Intent
import android.view.View
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.notesproutsn.R
import com.symmetricalpalmtree.notesproutsn.core.Dialogs
import com.symmetricalpalmtree.notesproutsn.core.OpeningOverlay
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.data.prefs.Surface
import com.symmetricalpalmtree.notesproutsn.data.prefs.SurfaceEntry
import com.symmetricalpalmtree.notesproutsn.data.prefs.SurfaceStack
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * The host's side of the Bible door (arc 37 / B0) — the [TagManagerEntry] shape with the surface
 * stack grafted on from [ExtensionScreenEntry], because unlike the tag screen the Bible **is** a
 * surface a cold launch puts back (the user's decision 13).
 *
 * What it owns:
 *  - **Availability.** [discovered] / [refresh] re-run the package query, and [button] is **GONE**
 *    when there is no trusted reader — never disabled: a disabled control is invisible on e-ink.
 *  - **The busy guard.** One showing at a time, latched **at the tap**.
 *  - **The wait.** [OpeningOverlay] goes up at tap time and the open runs behind its frame — a cold
 *    `ExtensionStores.lease` is seconds on the Nomad.
 *  - **The bind's life.** [BibleClient.finish] runs from the result callback and from [close].
 *  - **The surface stack** (arc 32 / RS1): pushed only once the launch actually happened, popped
 *    synchronously first thing in [onResult] and unconditionally in [close].
 *
 * There is **no EPD handoff here and there must not be one**, and **no chrome flag** crosses: the
 * reader carries no paper (the tag manager's two recorded answers, `docs/extensions.md` § "The
 * first tier-2 screen with no paper").
 *
 * **Arc 38 / R3** gave it the reference half of the point: [supportsReferences] (the method floor,
 * re-read on every discovery and reported through `onAvailabilityChanged` so the notebook's doors
 * can track it), [open] on a passage, and [resolve] — the one call that shows nothing at all.
 *
 * **B9 "Send"** (2026-09-13) gave it the calendar's return road: a caller with a notebook behind
 * it passes `sendEnabled`, the reader shows its Send to notebook button, and when the screen
 * returns `RESULT_BIBLE_SEND` the parked reference is taken over the bind still held and handed
 * to [onSent] **before** the bind is finished. Gated on [supportsSend] — an older reader is opened
 * without the flag and never shows the button.
 */
class BibleEntry(
    private val activity: AppCompatActivity,
    /** The standing bottom-bar button this entry shows or hides on every [refresh]. */
    private val button: View,
    /**
     * What the discovered reader can do, told after every [discovered] (arc 38 / R3) — the notebook
     * screen's Bible-reference doors track it, because they need a reader that understands
     * references and not merely one that exists. Called on Main; the value is [supportsReferences].
     */
    private val onAvailabilityChanged: (supportsReferences: Boolean) -> Unit = {},
    /**
     * B9: whether this door has a notebook behind it — the notebook screen's is true, the
     * library's false. Only a true here, against a reader that [supportsSend], puts the Send
     * button on the reader.
     */
    private val sendEnabled: Boolean = false,
    /**
     * B9: the reference the reader's Send parked, on Main, before the bind is finished — the
     * notebook lands it on the page as a Bible reference object. Never called for the library.
     */
    private val onSent: (ResolvedReference) -> Unit = {},
) {

    private val launcher: ActivityResultLauncher<Intent> =
        // Registered from the caller's onCreate — a launcher may not be registered after STARTED.
        activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            onResult(result)
        }

    private var ref: ProviderRef? = null
    private var client: BibleClient? = null
    private var opening = false

    private val stack = SurfaceStack(activity)
    private val token: String = UUID.randomUUID().toString()
    private val stackEntry: SurfaceEntry get() = SurfaceEntry(token, Surface.BIBLE)

    /**
     * Whether the discovered reader understands **references** (arc 38 / R3) — a reader declaring
     * [ExtensionContract.MIN_API_VERSION_FOR_BIBLE_REFERENCE] or above. The R1 tails are a *method*
     * floor, not an action floor: an older reader still serves the plain door, so this is read
     * separately from "is there a reader at all" and is what every reference door is gated on.
     *
     * Re-read after every [discovered], never captured — a package can be replaced under us.
     */
    var supportsReferences: Boolean = false
        private set

    /** B9: the discovered reader understands Send to notebook (`takeOutgoingReference`) — a
     *  reader declaring [ExtensionContract.MIN_API_VERSION_FOR_BIBLE_SEND] or above. */
    var supportsSend: Boolean = false
        private set

    /** Whether a trusted reader is installed **right now**. Suspends — it is a package query. */
    suspend fun discovered(): Boolean {
        val found = ExtensionRegistry.bible(activity)
        if (activity.isFinishing || activity.isDestroyed) return false
        ref = found
        supportsReferences =
            found != null && found.apiVersion >= ExtensionContract.MIN_API_VERSION_FOR_BIBLE_REFERENCE
        supportsSend =
            found != null && found.apiVersion >= ExtensionContract.MIN_API_VERSION_FOR_BIBLE_SEND
        button.visibility = if (found == null) View.GONE else View.VISIBLE
        onAvailabilityChanged(supportsReferences)
        return found != null
    }

    /** Re-discover and show or hide [button]. Called from the caller's `onResume` and after a
     *  failed open. The button is left as it was until the answer arrives. */
    fun refresh() {
        activity.lifecycleScope.launch { discovered() }
    }

    /**
     * Raise the box, then — behind it — pre-open the store, hold the bind and launch the screen for
     * a result. Any failure hides the box and explains itself in a dialog (a tap that did nothing
     * is never a toast on e-ink).
     */
    fun open() = show(reference = null)

    /**
     * Open the reader on one passage (arc 38 / R3) — a Bible link's follow. Identical in every
     * respect to [open] (the same overlay, the same one-showing-at-a-time latch, the same surface
     * stack) but for the opening call the client makes. Refused, silently, when the discovered
     * reader is too old to understand references: the caller (`LinkFollowFlow`) asks
     * [supportsReferences] first and explains in a dialog instead.
     */
    fun open(reference: String) = show(reference)

    /** Arc 39 "Lookup": the discovered reader, for the notebook to park a lookup against — read
     *  at the call, never captured (a package can be replaced under us). */
    val provider: ProviderRef? get() = ref

    private fun show(reference: String?) {
        val provider = ref ?: return
        if (opening) { Slog.d(TAG) { "open: already showing" }; return }
        if (reference != null && !supportsReferences) {
            Slog.d(TAG) { "open: the installed reader does not serve references" }
            return
        }
        opening = true
        OpeningOverlay.showThen(activity) {
            activity.lifecycleScope.launch {
                val fresh = BibleClient(activity, provider)
                client = fresh
                val intent = fresh.open(reference, sendEnabled = sendEnabled && supportsSend)
                if (activity.isFinishing || activity.isDestroyed) {
                    client = null; opening = false; fresh.finish(); return@launch
                }
                if (intent == null) { fail(fresh); return@launch }
                // The launch can still be refused (arc 34 / M8): an Intent that does not resolve is
                // found out first, and a refusal thrown by the launch takes the ordinary failure road.
                val launched = ScreenLaunch.attempt(
                    resolves = { intent.resolveActivity(activity.packageManager) != null },
                    beforeLaunch = {},
                    launch = { launcher.launch(intent) },
                    afterFailure = {},
                )
                if (launched != ScreenLaunch.Outcome.Launched) {
                    Slog.d(TAG) { "launch refused: ${(launched as? ScreenLaunch.Outcome.Refused)?.cause?.javaClass?.simpleName ?: "unresolved"}" }
                    fail(fresh)
                    return@launch
                }
                // On the stack only once the launch has actually happened (arc 32 / RS1).
                stack.attach(stackEntry)
            }
        }
    }

    /**
     * Ask the reader to read [text] as scripture references (arc 38 / R3) — bind-per-call, no
     * showing, nothing on screen. Null when there is no reader, when the one installed is too old
     * to be asked, or when the call failed or timed out: all four are the same answer to the
     * caller — **this was not checked, so nothing is created**, which is the arc's locked rule that
     * a dead Bible link can never exist.
     *
     * Neither the text nor the answer is logged here or in the client.
     */
    suspend fun resolve(text: String): ResolvedReference? {
        val provider = ref ?: return null
        if (!supportsReferences) return null
        return BibleClient.resolve(activity, provider, text)
    }

    private suspend fun fail(fresh: BibleClient) {
        client = null
        opening = false
        fresh.finish()
        OpeningOverlay.hide(activity)
        Dialogs.problem(activity, R.string.bible_failed_title, R.string.bible_failed_body)
        // It may have been disabled or replaced under us — ask again before it is offered.
        discovered()
    }

    /**
     * One showing is over. The bind is finished on a detached scope — the caller may be leaving.
     *
     * B9: on `RESULT_BIBLE_SEND` the parked reference is taken **first**, on the bind still held,
     * and handed to [onSent] before `finish` — the calendar's drain-then-finish order. A Send
     * from a door without a notebook behind it (there is none: the flag was never set) or from a
     * reader that parked nothing lands nothing, silently: the reader already closed.
     */
    private fun onResult(result: ActivityResult) {
        // First, and synchronously (arc 32 / RS1): a result callback runs **before** the host's own
        // `onResume`, whose `markTop` drops everything above it — this entry must already be gone.
        stack.pop(token)
        val open = client
        client = null
        Slog.d(TAG) { "bible screen returned: resultCode=${result.resultCode}" }
        val sent = result.resultCode == ExtensionContract.RESULT_BIBLE_SEND && sendEnabled
        MainScope().launch {
            try {
                if (sent && open != null) {
                    val reference = open.takeOutgoingReference()
                    if (reference != null && !activity.isFinishing && !activity.isDestroyed) onSent(reference)
                }
            } finally {
                try {
                    open?.finish()
                } finally {
                    opening = false
                }
            }
        }
    }

    /** The backstop: the bind must not outlive the screen that opened it, result or no result.
     *  Called from the caller's `onDestroy`. */
    fun close() {
        opening = false
        stack.pop(token)
        val open = client ?: return
        client = null
        MainScope().launch { open.finish() }
    }

    private companion object {
        const val TAG = "BibleEntry"
    }
}
