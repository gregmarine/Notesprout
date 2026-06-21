package com.notesprout.android

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.notesprout.android.crypto.KeySession
import com.notesprout.android.crypto.PassphraseStore
import com.notesprout.android.data.index.IndexRepository
import com.notesprout.android.data.index.NotesproutIndex
import com.notesprout.android.databinding.ActivityEncryptionSettingsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class EncryptionSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEncryptionSettingsBinding
    private val repository: IndexRepository by lazy { IndexRepository(NotesproutIndex.dao()) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEncryptionSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }

        binding.btnChangeGlobalPassphrase.setOnClickListener {
            // S6 wires the full rotation flow here; for now, placeholder.
            Toast.makeText(this, "Global passphrase rotation coming in next update.", Toast.LENGTH_LONG).show()
        }

        binding.btnForgetPassphrase.setOnClickListener { showForgetConfirm() }

        // S6 wires the resume banner; leave hidden until the rotation marker is present.
        binding.resumeRotationBanner.visibility = View.GONE
        binding.btnResumeRotation.setOnClickListener {
            // S6 fills this in.
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        lifecycleScope.launch {
            val isSet = withContext(Dispatchers.IO) { PassphraseStore.hasGlobalPassphrase(this@EncryptionSettingsActivity) }
            val count = withContext(Dispatchers.IO) { repository.countGlobalNotebooks() }

            binding.tvGlobalStatus.text = if (isSet) "Set" else "Not set"
            binding.tvGlobalCount.text = when (count) {
                0 -> "No notebooks use the global passphrase"
                1 -> "1 notebook uses the global passphrase"
                else -> "$count notebooks use the global passphrase"
            }
            binding.btnChangeGlobalPassphrase.isEnabled = isSet
            binding.btnForgetPassphrase.isEnabled = isSet
        }
    }

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
                // Also clear any in-memory global session so the next open re-prompts.
                KeySession.clear()
            }
            refreshStatus()
            Toast.makeText(this@EncryptionSettingsActivity, "Global passphrase forgotten on this device.", Toast.LENGTH_SHORT).show()
        }
    }
}
