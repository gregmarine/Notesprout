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
 */
class BibleEntry(
    private val activity: AppCompatActivity,
    /** The standing bottom-bar button this entry shows or hides on every [refresh]. */
    private val button: View,
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

    /** Whether a trusted reader is installed **right now**. Suspends — it is a package query. */
    suspend fun discovered(): Boolean {
        val found = ExtensionRegistry.bible(activity)
        if (activity.isFinishing || activity.isDestroyed) return false
        ref = found
        button.visibility = if (found == null) View.GONE else View.VISIBLE
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
    fun open() {
        val provider = ref ?: return
        if (opening) { Slog.d(TAG) { "open: already showing" }; return }
        opening = true
        OpeningOverlay.showThen(activity) {
            activity.lifecycleScope.launch {
                val fresh = BibleClient(activity, provider)
                client = fresh
                val intent = fresh.open()
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

    private suspend fun fail(fresh: BibleClient) {
        client = null
        opening = false
        fresh.finish()
        OpeningOverlay.hide(activity)
        Dialogs.problem(activity, R.string.bible_failed_title, R.string.bible_failed_body)
        // It may have been disabled or replaced under us — ask again before it is offered.
        discovered()
    }

    /** One showing is over. The bind is finished on a detached scope — the caller may be leaving. */
    private fun onResult(result: ActivityResult) {
        // First, and synchronously (arc 32 / RS1): a result callback runs **before** the host's own
        // `onResume`, whose `markTop` drops everything above it — this entry must already be gone.
        stack.pop(token)
        val open = client
        client = null
        Slog.d(TAG) { "bible screen returned: resultCode=${result.resultCode}" }
        MainScope().launch {
            try {
                open?.finish()
            } finally {
                opening = false
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
