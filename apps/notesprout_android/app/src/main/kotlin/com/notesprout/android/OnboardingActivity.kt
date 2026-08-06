package com.notesprout.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatButton
import androidx.lifecycle.lifecycleScope
import com.notesprout.android.core.IndexGuard
import com.notesprout.android.crypto.GlobalRotation
import com.notesprout.android.crypto.PassphrasePrompt
import com.notesprout.android.crypto.PassphraseStore
import com.notesprout.android.data.index.IndexRepository
import com.notesprout.android.data.index.NotesproutIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * First-launch onboarding: reveals the auto-generated recovery key (the global passphrase) so the
 * user can save it, since it's the only secret that unlocks their encrypted library on another
 * device or after a reinstall. Optionally lets them replace it with a memorable passphrase via the
 * global rotation (which now re-keys the index + training store too, keeping one recovery secret).
 *
 * Shown once — [shouldShow] gates on an acknowledgement flag. [BootstrapActivity] routes here before
 * [MainActivity] on the first launch that has a global passphrase, forwarding the original intent so
 * cold-launch restore / `.soil` deep links still run once onboarding is done.
 */
class OnboardingActivity : AppCompatActivity() {

    private val repository: IndexRepository by lazy { IndexRepository(NotesproutIndex.dao()) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Nothing has opened the index if Android rebuilt this task itself — see IndexGuard.
        if (!IndexGuard.ready(this)) return
        if (bounceIfIndexNotReady()) return
        setContentView(R.layout.activity_onboarding)

        val key = PassphraseStore.getGlobalPassphrase(this)
        if (key == null) {
            // Nothing to reveal (shouldn't happen behind shouldShow) — don't trap the user.
            acknowledgeAndContinue()
            return
        }

        findViewById<android.widget.TextView>(R.id.tvRecoveryKey).text = key

        findViewById<AppCompatButton>(R.id.btnCopyKey).setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Notesprout recovery key", key))
            Toast.makeText(this, "Recovery key copied", Toast.LENGTH_SHORT).show()
        }

        findViewById<AppCompatButton>(R.id.btnSavedIt).setOnClickListener {
            acknowledgeAndContinue()
        }

        findViewById<AppCompatButton>(R.id.btnSetPassphrase).setOnClickListener {
            startSetMemorablePassphrase(currentKey = key)
        }
    }

    // ── Replace the auto key with a memorable passphrase (index-aware rotation) ──

    private fun startSetMemorablePassphrase(currentKey: String) {
        lifecycleScope.launch {
            val newPassphrase = PassphrasePrompt.promptForPassphrase(
                this@OnboardingActivity,
                title = "Set a memorable passphrase",
                message = "This replaces your recovery key. It becomes the one secret that unlocks " +
                    "your library everywhere — choose something you won't forget, and still keep it safe.",
                confirm = true,
            ) ?: return@launch

            if (newPassphrase == currentKey) {
                acknowledgeAndContinue()
                return@launch
            }

            val cancelSignal = AtomicBoolean(false)
            val progress = AlertDialog.Builder(this@OnboardingActivity)
                .setTitle("Setting your passphrase")
                .setMessage("Applying…")
                .setCancelable(false)
                .create()
                .also { d ->
                    d.window?.setElevation(0f)
                    d.window?.setBackgroundDrawableResource(R.drawable.shape_dialog_bordered)
                    d.show()
                }

            val result = try {
                GlobalRotation.start(
                    context = this@OnboardingActivity,
                    repository = repository,
                    oldPassphrase = currentKey,
                    newPassphrase = newPassphrase,
                    onProgress = { done, total ->
                        withContext(Dispatchers.Main) { progress.setMessage("Applying $done / $total…") }
                    },
                    cancelSignal = cancelSignal,
                )
            } catch (e: Exception) {
                GlobalRotation.Result.Failed(e.message ?: "unknown error")
            } finally {
                progress.dismiss()
            }

            when (result) {
                is GlobalRotation.Result.Complete -> {
                    val msg = if (result.quarantined > 0)
                        "Passphrase set. ${result.quarantined} notebook${if (result.quarantined == 1) "" else "s"} kept their own passphrase."
                    else "Passphrase set."
                    Toast.makeText(this@OnboardingActivity, msg, Toast.LENGTH_LONG).show()
                    acknowledgeAndContinue()
                }
                is GlobalRotation.Result.Failed -> {
                    Toast.makeText(
                        this@OnboardingActivity,
                        "Couldn't set the passphrase: ${result.message}. Your recovery key is unchanged.",
                        Toast.LENGTH_LONG,
                    ).show()
                }
                is GlobalRotation.Result.Cancelled -> {
                    Toast.makeText(this@OnboardingActivity, "Cancelled.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun acknowledgeAndContinue() {
        markAcknowledged(this)
        startActivity(Intent(intent).setClass(this, MainActivity::class.java))
        finish()
    }

    companion object {
        private const val PREFS = "notesprout_onboarding"
        private const val KEY_ACK = "recovery_key_acknowledged"

        /** Show onboarding once, when a global passphrase exists but the user hasn't acknowledged it. */
        fun shouldShow(context: Context): Boolean {
            val acked = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ACK, false)
            return !acked && PassphraseStore.hasGlobalPassphrase(context)
        }

        fun markAcknowledged(context: Context) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_ACK, true).apply()
        }
    }
}
