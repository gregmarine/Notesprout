package com.symmetricalpalmtree.notesprout.library

import android.content.Intent
import android.view.View
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.TooltipCompat
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.notesprout.R
import com.symmetricalpalmtree.notesprout.core.Dialogs
import com.symmetricalpalmtree.notesprout.core.Slog
import com.symmetricalpalmtree.notesprout.extension.ExtensionRegistry
import com.symmetricalpalmtree.notesprout.extension.ProviderRef
import com.symmetricalpalmtree.notesprout.extension.ScratchPadClient
import com.symmetricalpalmtree.notesprout.notebook.ScratchPadFlow
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The library's Scratch Pad entry point (arc 6 / S1): the bottom-bar **notes** button beside Recents,
 * present only while a trusted `SCRATCH_PAD` extension is installed ([refresh] from the library's
 * `onResume`), opening the same pad with **no send target** (`sendEnabled = false` — the pad's Send
 * buttons are absent). The showing is the notebook flow's: `ScratchPadClient.open` → the launcher →
 * result of any code → `finish` (`end` → unbind → revoke). The library hosts no paper, so there is no
 * handoff to release.
 */
class ScratchPadLaunch(private val activity: AppCompatActivity, private val button: View) {

    private var ref: ProviderRef? = null
    private var client: ScratchPadClient? = null
    private var busy = false
    private var refreshGen = 0

    private val launcher: ActivityResultLauncher<Intent> =
        activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val c = client ?: return@registerForActivityResult
            client = null; busy = false
            Slog.d(TAG) { "result ${result.resultCode}" }
            ScratchPadFlow.appScope.launch { withContext(NonCancellable) { c.finish() } }
        }

    init {
        TooltipCompat.setTooltipText(button, button.contentDescription)
        button.setOnClickListener { open() }
    }

    /** Re-discover (IO) and show / hide the button. Newest generation wins. */
    fun refresh() {
        val gen = ++refreshGen
        activity.lifecycleScope.launch {
            val found = try { ExtensionRegistry.scratchPad(activity) } catch (e: Exception) { Slog.d(TAG) { "discovery failed: ${e.message}" }; null }
            if (gen != refreshGen || activity.isFinishing || activity.isDestroyed) return@launch
            ref = found
            button.visibility = if (found != null) View.VISIBLE else View.GONE
        }
    }

    private fun open() {
        if (busy) return
        val r = ref ?: return
        busy = true
        val c = ScratchPadClient(activity, r)
        client = c
        activity.lifecycleScope.launch {
            val intent = c.open(sendEnabled = false, openReceived = false)
            if (intent == null || activity.isFinishing || activity.isDestroyed) {
                client = null; busy = false
                if (intent != null) c.finish()
                else if (!activity.isFinishing && !activity.isDestroyed) {
                    Dialogs.problem(activity, R.string.cd_scratch_pad, activity.getString(R.string.scratch_failed, r.label))
                    refresh()   // a bind that fails because the package was disabled meanwhile (BOOX re-disables sideloads) hides the button now, not at the next resume
                }
                return@launch
            }
            try {
                launcher.launch(intent)
            } catch (e: Exception) {   // the screen vanished between the bind and the launch (BOOX freeze, a pad without the exported Activity)
                Slog.d(TAG) { "launch failed: ${e.javaClass.simpleName}: ${e.message}" }
                client = null; busy = false
                c.finish()
                if (!activity.isFinishing && !activity.isDestroyed) {
                    Dialogs.problem(activity, R.string.cd_scratch_pad, activity.getString(R.string.scratch_failed, r.label))
                    refresh()
                }
            }
        }
    }

    /** The library's `onDestroy`: finish an open client on a scope that outlives the screen. */
    fun close() {
        val c = client ?: return
        client = null; busy = false
        ScratchPadFlow.appScope.launch { withContext(NonCancellable) { c.finish() } }
    }

    private companion object {
        const val TAG = "ScratchPadLaunch"
    }
}
