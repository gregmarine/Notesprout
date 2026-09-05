package com.symmetricalpalmtree.notesproutsn.extension

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
 * The host's side of the **connect door** (arc 25 / V2) — the [TagManagerEntry] shape, for the
 * cloud point's one tier-2 screen.
 *
 * It owns exactly what that entry owns, and for the same reasons:
 *  - **Availability.** [discover] re-runs the package query every time the door is about to be
 *    offered — a package can be disabled or replaced under a standing screen. The caller makes its
 *    whole Cloud section **GONE** when the answer is null; never disabled, because a disabled
 *    control is invisible on e-ink and a door that lies is worse than one that is absent.
 *  - **The busy latch, at the tap.** E-ink gives a tap no feedback for hundreds of ms and the open
 *    is asynchronous twice over (the overlay's frame, then the store and the bind).
 *  - **The wait.** [OpeningOverlay] goes up at tap time and the open runs behind its first frame —
 *    a cold `ExtensionStores.open` is seconds on the Nomad and a tap with no answer for that long
 *    reads as a tap that missed.
 *  - **The bind's life.** [CloudConnectClient.finish] runs from the result callback and from
 *    [close] as the backstop for a caller destroyed while the sign-in is up.
 *
 * What it deliberately does **not** own: the account. The screen answers `RESULT_OK` only after the
 * token is in the store, so this class learns nothing from the result beyond "something may have
 * changed" — [onChanged] runs on **every** result, cancelled or not — and on a sign-in that could
 * not be opened at all — because the caller re-reads `status()` and that read is the truth. [wasConnected] tells the caller whether the result was the
 * `RESULT_OK` one, for a caller that wants to say so.
 *
 * There is **no EPD handoff here and there must not be one** — the sign-in carries no paper.
 */
class CloudConnectEntry(
    private val activity: AppCompatActivity,
    /** Run on Main after every result. `true` when the screen answered `RESULT_OK` — an account was
     *  connected; `false` when it was cancelled, which changes nothing but still re-renders. */
    private val onChanged: (wasConnected: Boolean) -> Unit = {},
) {

    private val launcher: ActivityResultLauncher<android.content.Intent> =
        // Registered from the caller's onCreate — a launcher may not be registered after STARTED.
        activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            onResult(result)
        }

    private var client: CloudConnectClient? = null
    private var opening = false

    /** The provider the last [discover] found, for the caller's own `status` / `disconnect` calls. */
    var ref: ProviderRef? = null
        private set

    /** Whether a trusted cloud provider is installed **right now**. Suspends — it is a package query. */
    suspend fun discover(): ProviderRef? {
        val found = ExtensionRegistry.cloud(activity)
        if (activity.isFinishing || activity.isDestroyed) return found
        ref = found
        return found
    }

    /** The answer [discover] last gave, without asking again. */
    val isAvailable: Boolean get() = ref != null

    /**
     * Raise the box, then — behind it — pre-open the store, hold the bind, `beginConnect`, and
     * launch the provider's sign-in for a result. Any failure hides the box and explains itself in a
     * dialog: a tap that did nothing is never a toast on e-ink.
     */
    fun open() {
        val provider = ref ?: return
        if (opening) { Slog.d(TAG) { "open: already showing" }; return }
        opening = true
        OpeningOverlay.showThen(activity) {
            activity.lifecycleScope.launch {
                val fresh = CloudConnectClient(activity, provider)
                client = fresh
                val intent = fresh.open()
                if (activity.isFinishing || activity.isDestroyed) {
                    client = null; opening = false; fresh.finish(); return@launch
                }
                if (intent == null) {
                    client = null
                    opening = false
                    fresh.finish()
                    OpeningOverlay.hide(activity)
                    Dialogs.problem(activity, R.string.cloud_connect_failed_title, R.string.cloud_connect_failed_body)
                    // It may have been disabled or replaced under us — ask again before it is offered.
                    discover()
                    // A result ALWAYS arrives (arc 25 / V5): a caller holding a latch across the
                    // sign-in — the import flow's source question — would otherwise wait for one
                    // that never comes. Not connected, and the caller re-renders as after a cancel.
                    if (!activity.isFinishing && !activity.isDestroyed) onChanged(false)
                    return@launch
                }
                launcher.launch(intent)
            }
        }
    }

    /** One showing is over. The bracket is finished on a detached scope — the caller may be leaving. */
    private fun onResult(result: ActivityResult) {
        val open = client
        client = null
        val connected = result.resultCode == android.app.Activity.RESULT_OK
        Slog.d(TAG) { "connect screen returned: resultCode=${result.resultCode}" }
        MainScope().launch {
            try {
                open?.finish()
            } finally {
                opening = false
            }
            // Both ways: a cancelled sign-in changed nothing, but the caller still re-renders — and
            // the re-render is a fresh `status()`, which is the only truth about the account.
            if (!activity.isFinishing && !activity.isDestroyed) onChanged(connected)
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
        const val TAG = "CloudConnectEntry"
    }
}
