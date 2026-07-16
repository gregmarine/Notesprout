package com.notesprout.android

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.notesprout.android.crypto.GlobalConversion
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
        binding.btnRevealRecoveryKey.setOnClickListener { showRecoveryKey() }
        binding.btnChangeGlobalPassphrase.setOnClickListener { startChangeGlobalPassphrase() }
        binding.btnForgetPassphrase.setOnClickListener { showForgetConfirm() }
        binding.btnResumeRotation.setOnClickListener { resumeRotation() }
        binding.btnEncryptAll.setOnClickListener { startBulkConversion() }
        binding.btnResumeConversion.setOnClickListener { resumeBulkConversion() }
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
            val hasConversionMarker = withContext(Dispatchers.IO) { GlobalConversion.hasMarker(this@EncryptionSettingsActivity) }
            val plaintextCount = withContext(Dispatchers.IO) { repository.getPlaintextNotebookIds().size }

            binding.tvGlobalStatus.text = if (isSet) "Set" else "Not set"
            binding.tvGlobalCount.text = when (count) {
                0 -> "No notebooks use the global passphrase"
                1 -> "1 notebook uses the global passphrase"
                else -> "$count notebooks use the global passphrase"
            }
            // Rotation and conversion are mutually exclusive with a marker of the other kind in flight.
            val busy = hasMarker || hasConversionMarker
            binding.btnChangeGlobalPassphrase.isEnabled = isSet && !busy
            binding.btnForgetPassphrase.isEnabled = isSet && !busy

            binding.resumeRotationBanner.visibility = if (hasMarker) View.VISIBLE else View.GONE
            binding.resumeConversionBanner.visibility = if (hasConversionMarker) View.VISIBLE else View.GONE

            // Offer bulk-encrypt only when a global key exists, plaintext notebooks remain, and no
            // sweep is already pending (the resume banner owns that case).
            binding.btnEncryptAll.apply {
                visibility = if (isSet && plaintextCount > 0 && !hasConversionMarker) View.VISIBLE else View.GONE
                text = "Encrypt All Notebooks ($plaintextCount)…"
                isEnabled = !hasMarker
            }
        }
    }

    // ── Reveal recovery key ───────────────────────────────────────────────────

    /** Show the current global passphrase (a.k.a. recovery key) so the user can re-save it. */
    private fun showRecoveryKey() {
        lifecycleScope.launch {
            val key = withContext(Dispatchers.IO) {
                PassphraseStore.getGlobalPassphrase(this@EncryptionSettingsActivity)
            }
            if (key == null) {
                Toast.makeText(this@EncryptionSettingsActivity, "No recovery key on this device.", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val keyView = android.widget.TextView(this@EncryptionSettingsActivity).apply {
                text = key
                setTextIsSelectable(true)
                typeface = android.graphics.Typeface.MONOSPACE
                textSize = 16f
                setTextColor(androidx.core.content.ContextCompat.getColor(context, R.color.inkBlack))
                setPadding(48, 40, 48, 8)
            }
            AlertDialog.Builder(this@EncryptionSettingsActivity)
                .setTitle("Recovery key")
                .setMessage("The one secret that unlocks your library on another device or after a reinstall. Keep it safe.")
                .setView(keyView)
                .setPositiveButton("Copy") { _, _ ->
                    val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("Notesprout recovery key", key))
                    Toast.makeText(this@EncryptionSettingsActivity, "Recovery key copied", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Close", null)
                .create()
                .also { d ->
                    d.show()
                    d.window?.setElevation(0f)
                    d.window?.setBackgroundDrawableResource(R.drawable.shape_bordered)
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
                repository = repository,
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
                val msg = buildString {
                    if (result.count == 0) append("Global passphrase changed.")
                    else append("Global passphrase changed (${result.count} notebook${if (result.count == 1) "" else "s"} re-keyed).")
                    if (result.quarantined > 0) {
                        append(" ${result.quarantined} notebook${if (result.quarantined == 1) "" else "s"} couldn't be re-keyed and now need their own passphrase.")
                    }
                }
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            }
            is GlobalRotation.Result.Cancelled -> {
                val extra = if (result.quarantined > 0) " ${result.quarantined} needed their own passphrase." else ""
                Toast.makeText(
                    this,
                    "Rotation paused. ${result.rotated} re-keyed, ${result.remaining} remaining.$extra Tap Resume to continue.",
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

    // ── Bulk-convert plaintext notebooks → GLOBAL ─────────────────────────────

    private fun startBulkConversion() {
        lifecycleScope.launch { runConversion(resume = false) }
    }

    private fun resumeBulkConversion() {
        lifecycleScope.launch { runConversion(resume = true) }
    }

    private suspend fun runConversion(resume: Boolean) {
        val globalPass = withContext(Dispatchers.IO) {
            PassphraseStore.getGlobalPassphrase(this@EncryptionSettingsActivity)
        }
        if (globalPass == null) {
            Toast.makeText(this, "No global passphrase on this device.", Toast.LENGTH_LONG).show()
            return
        }

        val cancelSignal = AtomicBoolean(false)
        val progress = AlertDialog.Builder(this)
            .setTitle("Encrypting Notebooks")
            .setMessage("Encrypting 0 / ……")
            .setNegativeButton("Cancel") { _, _ -> cancelSignal.set(true) }
            .setCancelable(false)
            .create()
            .also { d ->
                d.window?.setElevation(0f)
                d.window?.setBackgroundDrawableResource(R.drawable.shape_bordered)
            }
        progress.show()

        val onProgress: suspend (Int, Int) -> Unit = { done, total ->
            withContext(Dispatchers.Main) { progress.setMessage("Encrypting $done / $total…") }
        }
        val result = try {
            if (resume) {
                GlobalConversion.resume(this, repository, onProgress, cancelSignal)
            } else {
                GlobalConversion.start(this, repository, globalPass, onProgress, cancelSignal)
            }
        } finally {
            progress.dismiss()
        }
        handleConversionResult(result)
    }

    private fun handleConversionResult(result: GlobalConversion.Result) {
        val msg = when (result) {
            is GlobalConversion.Result.Complete -> buildString {
                append("Encrypted ${result.converted} notebook${if (result.converted == 1) "" else "s"}.")
                if (result.skipped > 0) append(" ${result.skipped} couldn't be encrypted and were left as-is.")
            }
            is GlobalConversion.Result.Cancelled ->
                "Paused. ${result.converted} encrypted, ${result.remaining} remaining. Tap Resume to continue."
            is GlobalConversion.Result.Failed -> {
                if (result.message == "no_cached_global")
                    "The global passphrase is no longer cached. Set it again to continue."
                else "Encryption failed: ${result.message}"
            }
        }
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        refreshStatus()
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
