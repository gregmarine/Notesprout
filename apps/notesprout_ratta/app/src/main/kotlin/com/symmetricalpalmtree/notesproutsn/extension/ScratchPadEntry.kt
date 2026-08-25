package com.symmetricalpalmtree.notesproutsn.extension

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
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

/**
 * The host's side of the Scratch Pad entry button (arc 11 / J4) — **one class for both doors**, the
 * library's and the notebook's, because everything about them is the same except the one line that
 * is not: the notebook hands the EPD pipeline over before it launches, and the library has no
 * pipeline to hand over. The alternative was two near-identical files, which is exactly the
 * sibling-copy trap `:sn-screen` exists to keep out of this app.
 *
 * What it owns:
 *  - **Visibility.** The button is `GONE` unless a trusted `SCRATCH_PAD` extension is installed, and
 *    discovery re-runs on every [refresh] (each `onResume`) **and after a failed open** — a package
 *    can be disabled or replaced under us, and a button that lies is worse than one that is absent.
 *    Never `isEnabled = false`: a disabled control is invisible on e-ink.
 *  - **The busy guard.** One showing at a time. E-ink gives a tap no feedback for hundreds of ms, so
 *    the second tap is taken as read.
 *  - **The wait.** [OpeningOverlay] goes up at tap time and the open runs only once its frame is on
 *    the glass: a **cold** open measured 3 123 ms on the Nomad (SQLCipher's KDF creating the store)
 *    against 114 ms warm, and a tap with no answer for three seconds reads as a tap that missed.
 *    In the notebook this rides the C1 frame-silence exception — it is the same act as the Contents
 *    and Recents buttons, a deliberate chrome tap that raises a full-screen thing, and the tap has
 *    already released the render on its way through `dispatchTouchEvent`.
 *  - **[beforeLaunch]**, run in the instant between the successful `begin` and the launch: the
 *    notebook's `paper.releaseForHandoff()`. The pad releases its own before every `finish()`.
 *  - **The bind's life.** [ScratchPadClient.finish] runs from the result callback, and from
 *    [close] as the backstop for a caller destroyed while the pad is still up.
 *
 * The pad opens **no `.soil`**, and the notebook is **not** sealed behind it — the one way this hop
 * differs from arc 10's notebook switch. What the notebook gives up is the pipeline, not its data:
 * its session, its undo stack and its unsaved page are all still there when the result comes back
 * (and J5's paste depends on exactly that).
 */
class ScratchPadEntry(
    private val activity: AppCompatActivity,
    private val button: View,
    /** Run immediately before the screen is launched — the notebook's `releaseForHandoff()`. */
    private val beforeLaunch: () -> Unit = {},
    /** The result of one showing. J4 has no transfers; J5 reads `RESULT_SCRATCH_SEND` here. */
    private val onResult: (ActivityResult) -> Unit = {},
) {

    private val launcher: ActivityResultLauncher<android.content.Intent> =
        // Registered from the caller's onCreate — a launcher may not be registered after STARTED.
        activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val open = client
            client = null
            opening = false
            Slog.d(TAG) { "scratch pad returned: resultCode=${result.resultCode}" }
            onResult(result)
            // A detached scope: `finish` has an `end()` call plus an unbind and a revoke to run, and
            // the caller may be on its way out.
            MainScope().launch { open?.finish() }
        }

    private var ref: ProviderRef? = null
    private var client: ScratchPadClient? = null

    /**
     * Latched **at the tap**, not when the client lands. E-ink gives a tap no feedback for hundreds
     * of ms so users tap twice, and the open is asynchronous twice over (a pre-draw hop, then the
     * store and the bind): a guard that watched [client] alone would still be open on the second
     * tap and start a second showing. Released with the result, or the moment the open fails.
     */
    private var opening = false

    /**
     * Re-discover and show or hide the button. Called from the caller's `onResume` and after a
     * failed open. Discovery is IO; the button is left as it was until the answer arrives.
     */
    fun refresh() {
        activity.lifecycleScope.launch {
            val found = ExtensionRegistry.scratchPad(activity)
            if (activity.isFinishing || activity.isDestroyed) return@launch
            ref = found
            button.visibility = if (found == null) View.GONE else View.VISIBLE
        }
    }

    /**
     * Tap. Raises the box, then — behind it — pre-opens the store on IO, holds the bind, `begin`s,
     * runs [beforeLaunch] and launches the screen for a result. Any failure hides the box, explains
     * itself in a dialog (a tap that did nothing is never a toast on e-ink) and re-runs discovery,
     * so a package disabled under us takes its button with it.
     */
    fun open() {
        val provider = ref ?: return
        if (opening) { Slog.d(TAG) { "open: already showing" }; return }
        opening = true
        OpeningOverlay.showThen(activity) {
            activity.lifecycleScope.launch {
                val fresh = ScratchPadClient(activity, provider)
                client = fresh
                val intent = fresh.open(sendEnabled = false, openReceived = false)
                if (activity.isFinishing || activity.isDestroyed) {
                    client = null; opening = false; fresh.finish(); return@launch
                }
                if (intent == null) {
                    client = null
                    opening = false
                    OpeningOverlay.hide(activity)
                    Dialogs.problem(activity, R.string.scratch_failed_title, R.string.scratch_failed_body)
                    refresh()   // it may have been disabled or replaced under us
                    return@launch
                }
                // The pipeline goes over the instant before the launch, and not one step earlier:
                // until here the open could still have failed and left this screen writing.
                beforeLaunch()
                launcher.launch(intent)
            }
        }
    }

    /** The backstop: the bind must not outlive the screen that opened it, result or no result.
     *  Called from the caller's `onDestroy`. */
    fun close() {
        opening = false
        val open = client ?: return
        client = null
        MainScope().launch { open.finish() }
    }

    private companion object {
        const val TAG = "ScratchPadEntry"
    }
}
