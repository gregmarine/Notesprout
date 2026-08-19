package com.symmetricalpalmtree.notesprout.notebook

import android.app.Activity
import android.content.Intent
import android.view.View
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.TooltipCompat
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.gpaper.core.PaperView
import com.symmetricalpalmtree.notesprout.R
import com.symmetricalpalmtree.notesprout.core.Dialogs
import com.symmetricalpalmtree.notesprout.core.Slog
import com.symmetricalpalmtree.notesprout.extension.ExtensionRegistry
import com.symmetricalpalmtree.notesprout.extension.ProviderRef
import com.symmetricalpalmtree.notesprout.extension.ScratchPadClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The notebook's side of the Scratch Pad (arc 6 / S1): the top-bar **notes** button — present only
 * while a trusted `SCRATCH_PAD` extension is installed ([refresh], re-discovered on every resume, the
 * Naming / Contents rule) — and the showing of the extension's screen: [open] = a [busy] guard (a
 * second tap is dropped) → [ScratchPadClient.open] (store pre-open → held bind → `begin`) →
 * `paper.releaseForHandoff()` (rule 27 — the pad's process claims the EPD pipeline next; the notebook's
 * pen stays live through the open, which can be seconds on a cold store) → the Intent through the
 * [launcher] (`startActivityForResult`-style, so the extension's caller check can pass — risk 2);
 * a null Intent → the core's `scratch_failed` dialog naming the extension. The result (any code) →
 * [ScratchPadClient.finish] (`end` → unbind → revoke) — and the host's `onResume` re-arms its own
 * paper. [close] from the host's `onDestroy` finishes an open client on a scope that outlives it.
 * S2 adds the core `scratch` toolbar action + the two transfers here.
 */
class ScratchPadFlow(
    private val activity: AppCompatActivity,
    private val paper: PaperView,
    private val button: View,
    /** `opened && !closing` on the host. */
    private val alive: () -> Boolean,
    private val whenPenIdle: (() -> Unit) -> Unit,
    /** Called when the button's visibility changed — the host re-pushes its exclusion rects. */
    private val onVisibilityChanged: () -> Unit,
) {
    private var ref: ProviderRef? = null
    private var client: ScratchPadClient? = null
    private var busy = false
    private var refreshGen = 0

    /** Registered at construction — the host builds the flow in `onCreate`. */
    private val launcher: ActivityResultLauncher<Intent> =
        activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result -> onResult(result.resultCode) }

    init {
        TooltipCompat.setTooltipText(button, button.contentDescription)
        button.setOnClickListener { open() }
    }

    /** Re-discover the extension (IO) and show / hide the button (pen-idle). Newest generation wins. */
    fun refresh() {
        val gen = ++refreshGen
        activity.lifecycleScope.launch {
            val found = try { ExtensionRegistry.scratchPad(activity) } catch (e: Exception) { Slog.d(TAG) { "discovery failed: ${e.message}" }; null }
            if (gen != refreshGen || activity.isFinishing || activity.isDestroyed) return@launch
            ref = found
            whenPenIdle {
                val vis = if (ref != null) View.VISIBLE else View.GONE
                if (button.visibility != vis) { button.visibility = vis; onVisibilityChanged() }
            }
        }
    }

    /** The top-bar tap: open the pad from this notebook (Send enabled; nothing received). */
    fun open() {
        if (busy || !alive()) return
        val r = ref ?: return
        busy = true
        paper.releaseRender()
        val c = ScratchPadClient(activity, r)
        client = c
        activity.lifecycleScope.launch {
            val intent = c.open(sendEnabled = true, openReceived = false)
            if (intent == null || !alive() || activity.isFinishing || activity.isDestroyed) {
                client = null; busy = false
                if (intent != null) c.finish()
                else if (!activity.isFinishing && !activity.isDestroyed) Dialogs.problem(activity, R.string.cd_scratch_pad, activity.getString(R.string.scratch_failed, r.label))
                return@launch
            }
            Slog.d(TAG) { "launching the pad" }
            paper.releaseForHandoff()   // immediately before launching another paper-hosting screen (g-paper §Lifecycle)
            launcher.launch(intent)
        }
    }

    private fun onResult(resultCode: Int) {
        val c = client ?: return
        client = null
        busy = false
        Slog.d(TAG) { "result $resultCode" + if (resultCode == Activity.RESULT_CANCELED) " (cancelled)" else "" }
        appScope.launch { withContext(NonCancellable) { c.finish() } }
    }

    /** The host's `onDestroy`: an open client is finished on a scope that outlives the screen. */
    fun close() {
        val c = client ?: return
        client = null
        busy = false
        appScope.launch { withContext(NonCancellable) { c.finish() } }
    }

    companion object {
        private const val TAG = "ScratchPadFlow"
        /** Outlives the Activity so `end` → unbind → revoke always completes. */
        internal val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }
}
