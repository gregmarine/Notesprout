package com.symmetricalpalmtree.notesproutsn.bootstrap

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.notesproutsn.R
import com.symmetricalpalmtree.notesproutsn.core.Dialogs
import com.symmetricalpalmtree.notesproutsn.crypto.PassphraseStore
import com.symmetricalpalmtree.notesproutsn.data.index.SnIndex
import com.symmetricalpalmtree.notesproutsn.library.LibraryActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Launcher gate — **the only thing that opens the index.** Runs the open state machine
 * ([SnIndex.ensureReady]) behind a plain paper-white screen, then forwards:
 *  - first launch (or the recovery key was never acknowledged) → [RecoveryKeyActivity]
 *  - needs unlock → [UnlockActivity]
 *  - otherwise → [LibraryActivity]
 *
 * `noHistory` + `finish()`: never on the back stack. Every other screen bounces back here through
 * `IndexGuard` when the index isn't open (a task Android rebuilt after a background kill).
 * A boot failure shows Retry / Close instead of crash-looping the launcher.
 */
class BootstrapActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this).apply {
            setBackgroundColor(ContextCompat.getColor(this@BootstrapActivity, R.color.paperWhite))
        }
        setContentView(root)
        // Reveal "Preparing…" only if the open is genuinely slow (first-launch KDF); a warm raw-key
        // open is instant and must not flash text.
        val reveal = Runnable { root.addView(preparingView()) }
        handler.postDelayed(reveal, REVEAL_DELAY_MS)
        launchBoot(root, reveal)
    }

    private fun launchBoot(root: FrameLayout, reveal: Runnable) {
        lifecycleScope.launch {
            try {
                boot()
            } catch (e: CancellationException) {
                // The activity is going away (Home during the first-boot KDF) — not a boot failure,
                // and a dialog on a dead window is a BadTokenException. Let cancellation pass.
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "boot failed", e)
                if (isFinishing || isDestroyed) return@launch
                root.removeAllViews()
                Dialogs.style(
                    AlertDialog.Builder(this@BootstrapActivity)
                        .setTitle(R.string.boot_error_title)
                        .setMessage(getString(R.string.boot_error_body, e.message ?: e.javaClass.simpleName))
                        .setCancelable(false)
                        .setPositiveButton(R.string.retry) { _, _ -> launchBoot(root, reveal) }
                        .setNegativeButton(R.string.close_app) { _, _ -> finishAffinity() }
                        .create()
                ).show()
            } finally {
                handler.removeCallbacks(reveal)
            }
        }
    }

    private suspend fun boot() {
        when (SnIndex.ensureReady(this)) {
            SnIndex.PrepareOutcome.READY,
            SnIndex.PrepareOutcome.FIRST_LAUNCH -> {
                val next = if (PassphraseStore.isRecoveryKeyAcknowledged(this)) LibraryActivity::class.java
                           else RecoveryKeyActivity::class.java
                forward(next)
            }
            SnIndex.PrepareOutcome.NEEDS_UNLOCK -> forward(UnlockActivity::class.java)
            SnIndex.PrepareOutcome.FOREIGN_FILE -> throw IllegalStateException(getString(R.string.boot_error_foreign))
            SnIndex.PrepareOutcome.DAMAGED_FILE -> throw IllegalStateException(getString(R.string.boot_error_damaged))
        }
    }

    /** A clean in-app intent — never the received launcher intent (its flags re-trigger launcher
     *  task routing and can drop the window back on the home screen). */
    private fun forward(next: Class<*>) {
        startActivity(Intent(this, next))
        finish()
    }

    private fun preparingView(): LinearLayout {
        val ink = ContextCompat.getColor(this, R.color.inkBlack)
        return LinearLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(TextView(this@BootstrapActivity).apply {
                text = getString(R.string.app_name)
                textSize = 22f
                setTextColor(ink)
                gravity = Gravity.CENTER
            })
            addView(TextView(this@BootstrapActivity).apply {
                text = getString(R.string.boot_preparing)
                textSize = 14f
                setTextColor(ink)
                gravity = Gravity.CENTER
                setPadding(0, 24, 0, 0)
            })
        }
    }

    companion object {
        private const val TAG = "BootstrapActivity"
        private const val REVEAL_DELAY_MS = 450L
    }
}
