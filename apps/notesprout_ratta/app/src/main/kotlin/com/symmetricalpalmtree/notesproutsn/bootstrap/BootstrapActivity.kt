package com.symmetricalpalmtree.notesproutsn.bootstrap

import android.app.Activity
import android.content.Context
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
import com.symmetricalpalmtree.notesproutsn.crypto.GlobalRotation
import com.symmetricalpalmtree.notesproutsn.crypto.KeySession
import com.symmetricalpalmtree.notesproutsn.crypto.PassphraseStore
import com.symmetricalpalmtree.notesproutsn.crypto.SoilRekey
import com.symmetricalpalmtree.notesproutsn.data.index.SnIndex
import com.symmetricalpalmtree.notesproutsn.encryption.EncryptionActivity
import com.symmetricalpalmtree.notesproutsn.library.LibraryActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Launcher gate — **the only thing that opens the index.** Runs the open state machine
 * ([SnIndex.ensureReady]) behind a plain paper-white screen, then forwards:
 *  - first launch (or the recovery key was never acknowledged) → [RecoveryKeyActivity]
 *  - needs unlock → [UnlockActivity]
 *  - a rotation marker exists (arc 26 / U3) → the Encryption screen, whose banner is the resume
 *  - otherwise → [LibraryActivity] ([BootstrapRoute] is the decision)
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
                // Arc 26 / U2: finish any rekey commit a kill interrupted — `X.rekey.tmp` /
                // `X.old.bak` beside a Garden file — before the library can list it. The cached
                // global is the trusted key; a file it does not open is left exactly where it is.
                // The verifier knows a rotation marker's new key too (arc 26 / U3) — the files a
                // rotation already re-keyed verify under it, not under the cached global.
                if (KeySession.get() != null) SoilRekey.recoverGarden(this, GlobalRotation.trustedVerifier(this))
                // Arc 17 / K1: purge soft-deleted index rows while nothing else is reading —
                // gated on an EXISTS probe, so the ordinary launch pays one trivial query.
                SnIndex.compactIfNeeded()
                forwardAfterOpen(this, intent.getBooleanExtra(EXTRA_THEN_BACKUP, false))
                finish()
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

        /** Arc 26 / U3: a rotation's completion dialog chose *Back up now* — the library opens the
         *  Backup screen once it is up. A boolean, never a secret. */
        const val EXTRA_THEN_BACKUP = "then_backup"

        /**
         * The relaunch a finished rotation ends in: a clean task rooted here, so the index (closed
         * for its own rekey) reopens under the new key and every screen is rebuilt behind
         * `IndexGuard`. The caller calls `finishAffinity()` after starting this.
         */
        fun relaunchIntent(context: Context, thenBackup: Boolean): Intent =
            Intent(context, BootstrapActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .putExtra(EXTRA_THEN_BACKUP, thenBackup)

        /**
         * Send an opened index's person on ([BootstrapRoute.afterOpen]) — shared by this screen,
         * Unlock and the recovery-key screen's Continue. Starts the next Activity; the caller
         * finishes itself. [thenBackup] rides along where [BootstrapRoute.carriesThenBackup] says.
         */
        fun forwardAfterOpen(from: Activity, thenBackup: Boolean) {
            val next = BootstrapRoute.afterOpen(
                acknowledged = PassphraseStore.isRecoveryKeyAcknowledged(from),
                hasMarker = GlobalRotation.hasMarker(from),
            )
            val target = when (next) {
                BootstrapRoute.Next.RECOVERY_KEY -> RecoveryKeyActivity::class.java
                BootstrapRoute.Next.ENCRYPTION -> EncryptionActivity::class.java
                BootstrapRoute.Next.LIBRARY -> LibraryActivity::class.java
            }
            // A clean in-app intent — never the received launcher intent.
            val intent = Intent(from, target)
            if (thenBackup && BootstrapRoute.carriesThenBackup(next)) intent.putExtra(EXTRA_THEN_BACKUP, true)
            from.startActivity(intent)
        }
    }
}
