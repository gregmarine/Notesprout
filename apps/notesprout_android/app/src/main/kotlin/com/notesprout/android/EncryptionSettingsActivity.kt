package com.notesprout.android

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.notesprout.android.crypto.GlobalRotation
import com.notesprout.android.crypto.KeySession
import com.notesprout.android.crypto.PassphrasePrompt
import com.notesprout.android.crypto.PassphraseStore
import com.notesprout.android.crypto.SoilCrypto
import com.notesprout.android.data.index.IndexRepository
import com.notesprout.android.data.index.NotesproutIndex
import com.notesprout.android.data.soilFile
import com.notesprout.android.databinding.ActivityEncryptionSettingsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

class EncryptionSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEncryptionSettingsBinding
    private val repository: IndexRepository by lazy { IndexRepository(NotesproutIndex.dao()) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEncryptionSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnChangeGlobalPassphrase.setOnClickListener { startChangeGlobalPassphrase() }
        binding.btnForgetPassphrase.setOnClickListener { showForgetConfirm() }
        binding.btnResumeRotation.setOnClickListener { resumeRotation() }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        lifecycleScope.launch {
            val isSet = withContext(Dispatchers.IO) { PassphraseStore.hasGlobalPassphrase(this@EncryptionSettingsActivity) }
            val count = withContext(Dispatchers.IO) { repository.countGlobalNotebooks() }
            val hasMarker = withContext(Dispatchers.IO) { GlobalRotation.hasMarker(this@EncryptionSettingsActivity) }

            binding.tvGlobalStatus.text = if (isSet) "Set" else "Not set"
            binding.tvGlobalCount.text = when (count) {
                0 -> "No notebooks use the global passphrase"
                1 -> "1 notebook uses the global passphrase"
                else -> "$count notebooks use the global passphrase"
            }
            binding.btnChangeGlobalPassphrase.isEnabled = isSet && !hasMarker
            binding.btnForgetPassphrase.isEnabled = isSet && !hasMarker

            if (hasMarker) {
                binding.resumeRotationBanner.visibility = View.VISIBLE
            } else {
                binding.resumeRotationBanner.visibility = View.GONE
            }
        }
    }

    // ── Change global passphrase (fresh rotation) ─────────────────────────────

    private fun startChangeGlobalPassphrase() {
        lifecycleScope.launch {
            // Step 1: verify the current global passphrase.
            val oldPassphrase = verifyCurrentGlobal() ?: return@launch

            // Step 2: prompt for the new passphrase + confirm.
            val newPassphrase = PassphrasePrompt.promptForPassphrase(
                this@EncryptionSettingsActivity,
                title = "New Global Passphrase",
                message = "Enter a new global passphrase. All notebooks using the global passphrase will be re-keyed.",
                confirm = true,
            ) ?: return@launch

            if (newPassphrase == oldPassphrase) {
                Toast.makeText(this@EncryptionSettingsActivity, "New passphrase is the same as the current one.", Toast.LENGTH_SHORT).show()
                return@launch
            }

            // Step 3: run the rotation with a progress dialog.
            runRotation(oldPassphrase, newPassphrase)
        }
    }

    /**
     * Prompts for the current global passphrase and verifies it.
     * If the cached passphrase exists, verifies against the first GLOBAL notebook (or just against
     * the cache if there are none yet). Returns null if the user cancels or the passphrase is wrong.
     */
    private suspend fun verifyCurrentGlobal(): String? {
        val cached = withContext(Dispatchers.IO) { PassphraseStore.getGlobalPassphrase(this@EncryptionSettingsActivity) }
        var promptMsg = "Enter the current global passphrase to proceed."
        while (true) {
            val entered = PassphrasePrompt.promptForPassphrase(
                this@EncryptionSettingsActivity,
                title = "Current Global Passphrase",
                message = promptMsg,
            ) ?: return null

            // Quick match against cache — avoids a file open when possible.
            if (cached != null && entered == cached) return entered

            // Verify against an actual GLOBAL notebook if one exists.
            val firstId = withContext(Dispatchers.IO) { repository.getGlobalNotebookIds().firstOrNull() }
            if (firstId != null) {
                val file = withContext(Dispatchers.IO) { soilFile(this@EncryptionSettingsActivity, firstId) }
                val valid = withContext(Dispatchers.IO) { SoilCrypto.verifyPassphrase(file, entered) }
                if (valid) return entered
            } else if (cached == null) {
                // No notebooks and no cache — passphrase can't be verified. Accept anything.
                return entered
            }

            promptMsg = "Wrong passphrase. Try again."
        }
    }

    // ── Resume interrupted rotation ───────────────────────────────────────────

    private fun resumeRotation() {
        lifecycleScope.launch {
            val hasCached = withContext(Dispatchers.IO) { PassphraseStore.hasGlobalPassphrase(this@EncryptionSettingsActivity) }
            if (!hasCached) {
                // The user forgot the global passphrase mid-rotation — we need the old one to
                // finish re-keying the remaining notebooks.
                Toast.makeText(
                    this@EncryptionSettingsActivity,
                    "The global passphrase was cleared. Re-enter it to resume rotation.",
                    Toast.LENGTH_LONG
                ).show()
                val old = PassphrasePrompt.promptForPassphrase(
                    this@EncryptionSettingsActivity,
                    title = "Old Global Passphrase",
                    message = "Enter the old global passphrase to resume the rotation.",
                ) ?: return@launch
                // Re-cache so GlobalRotation.resume() can pick it up.
                withContext(Dispatchers.IO) { PassphraseStore.setGlobalPassphrase(this@EncryptionSettingsActivity, old) }
            }
            runRotationResume()
        }
    }

    // ── Shared rotation runner ────────────────────────────────────────────────

    private suspend fun runRotation(oldPassphrase: String, newPassphrase: String) {
        val cancelSignal = AtomicBoolean(false)
        val progressDialog = buildProgressDialog(cancelSignal)
        progressDialog.show()

        val result = try {
            GlobalRotation.start(
                context = this@EncryptionSettingsActivity,
                repository = repository,
                oldPassphrase = oldPassphrase,
                newPassphrase = newPassphrase,
                onProgress = { done, total ->
                    withContext(Dispatchers.Main) {
                        progressDialog.setMessage("Re-keying $done / $total…")
                    }
                },
                cancelSignal = cancelSignal,
            )
        } finally {
            progressDialog.dismiss()
        }

        handleRotationResult(result)
    }

    private suspend fun runRotationResume() {
        val cancelSignal = AtomicBoolean(false)
        val progressDialog = buildProgressDialog(cancelSignal)
        progressDialog.show()

        val result = try {
            val r = GlobalRotation.resume(
                context = this@EncryptionSettingsActivity,
                onProgress = { done, total ->
                    withContext(Dispatchers.Main) {
                        progressDialog.setMessage("Re-keying $done / $total…")
                    }
                },
                cancelSignal = cancelSignal,
            )
            // If global cache was cleared mid-rotation, resume returns a sentinel.
            if (r is GlobalRotation.Result.Failed && r.message == "no_cached_global") {
                GlobalRotation.Result.Failed("The old global passphrase is no longer cached. Use 'Change Global Passphrase' to restart.")
            } else r
        } finally {
            progressDialog.dismiss()
        }

        handleRotationResult(result)
    }

    private fun handleRotationResult(result: GlobalRotation.Result) {
        when (result) {
            is GlobalRotation.Result.Complete -> {
                val msg = if (result.count == 0) "Global passphrase changed."
                else "Global passphrase changed (${result.count} notebook${if (result.count == 1) "" else "s"} re-keyed)."
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            }
            is GlobalRotation.Result.Cancelled -> {
                Toast.makeText(
                    this,
                    "Rotation paused. ${result.rotated} re-keyed, ${result.remaining} remaining. Tap Resume to continue.",
                    Toast.LENGTH_LONG
                ).show()
            }
            is GlobalRotation.Result.Failed -> {
                Toast.makeText(this, "Rotation failed: ${result.message}", Toast.LENGTH_LONG).show()
            }
        }
        refreshStatus()
    }

    private fun buildProgressDialog(cancelSignal: AtomicBoolean): AlertDialog {
        return AlertDialog.Builder(this)
            .setTitle("Changing Global Passphrase")
            .setMessage("Re-keying 0 / ……")
            .setNegativeButton("Cancel") { _, _ -> cancelSignal.set(true) }
            .setCancelable(false)
            .create()
            .also { d ->
                d.window?.setElevation(0f)
                d.window?.setBackgroundDrawableResource(R.drawable.shape_bordered)
            }
    }

    // ── Forget passphrase ─────────────────────────────────────────────────────

    private fun showForgetConfirm() {
        AlertDialog.Builder(this)
            .setTitle("Forget Global Passphrase")
            .setMessage(
                "The cached global passphrase will be removed from this device. " +
                "Notebooks encrypted with the global passphrase will prompt once the next time they are opened. " +
                "No notebooks will be decrypted or modified."
            )
            .setPositiveButton("Forget") { _, _ -> forgetPassphrase() }
            .setNegativeButton("Cancel", null)
            .create()
            .also { d ->
                d.show()
                d.window?.setElevation(0f)
                d.window?.setBackgroundDrawableResource(R.drawable.shape_bordered)
            }
    }

    private fun forgetPassphrase() {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                PassphraseStore.clearGlobalPassphrase(this@EncryptionSettingsActivity)
                KeySession.clear()
            }
            refreshStatus()
            Toast.makeText(this@EncryptionSettingsActivity, "Global passphrase forgotten on this device.", Toast.LENGTH_SHORT).show()
        }
    }
}
