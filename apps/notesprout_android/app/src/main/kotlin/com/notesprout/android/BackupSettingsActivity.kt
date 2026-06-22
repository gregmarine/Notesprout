package com.notesprout.android

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.notesprout.android.data.backup.BackupConfig
import com.notesprout.android.data.backup.DeviceIdentity
import com.notesprout.android.data.backup.DriveTokenStore
import com.notesprout.android.data.index.IndexRepository
import com.notesprout.android.data.index.NotesproutIndex
import com.notesprout.android.databinding.ActivityBackupSettingsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

class BackupSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBackupSettingsBinding
    private val repository: IndexRepository by lazy { IndexRepository(NotesproutIndex.dao()) }

    private val pickLocalTreeLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) onLocalTreePicked(uri)
    }

    private val driveAuthLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val email = result.data?.getStringExtra(DriveAuthActivity.EXTRA_EMAIL)
            onDriveConnected(email)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBackupSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnChooseLocal.setOnClickListener { pickLocalTreeLauncher.launch(null) }
        binding.btnSaveDeviceName.setOnClickListener { saveDeviceName() }

        binding.btnConnectDrive.setOnClickListener { connectDrive() }
        binding.btnDisconnectDrive.setOnClickListener { disconnectDrive() }

        binding.switchLocalEnabled.setOnCheckedChangeListener { _, checked ->
            persistToggle(isLocal = true, enabled = checked)
        }
        binding.switchDriveEnabled.setOnCheckedChangeListener { _, checked ->
            persistToggle(isLocal = false, enabled = checked)
        }

        // TODO (Session 3): wire btnBackUpNow to the backup engine
        binding.btnBackUpNow.isEnabled = false
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
    }

    private fun refreshUi() {
        lifecycleScope.launch {
            val config = withContext(Dispatchers.IO) {
                repository.ensureBackupConfig(DeviceIdentity.defaultDeviceFolderName())
            }
            applyConfigToUi(config)
        }
    }

    private fun applyConfigToUi(config: BackupConfig) {
        binding.etDeviceFolderName.setText(config.deviceFolderName)

        // Local section
        val localUri = config.localTreeUri?.let { Uri.parse(it) }
        binding.tvLocalStatus.text = if (localUri != null) {
            localUri.lastPathSegment?.substringAfterLast(':') ?: localUri.toString()
        } else {
            "Not configured"
        }
        binding.switchLocalEnabled.isEnabled = localUri != null
        binding.switchLocalEnabled.setOnCheckedChangeListener(null)
        binding.switchLocalEnabled.isChecked = config.localEnabled && localUri != null
        binding.switchLocalEnabled.setOnCheckedChangeListener { _, checked ->
            persistToggle(isLocal = true, enabled = checked)
        }

        // Drive section
        val connected = config.driveAccountEmail != null
        binding.tvDriveStatus.text = if (connected) "Connected as ${config.driveAccountEmail}"
                                     else "Not connected"
        binding.btnConnectDrive.isVisible = !connected
        binding.btnDisconnectDrive.isVisible = connected
        binding.switchDriveEnabled.isEnabled = connected
        binding.switchDriveEnabled.setOnCheckedChangeListener(null)
        binding.switchDriveEnabled.isChecked = config.driveEnabled && connected
        binding.switchDriveEnabled.setOnCheckedChangeListener { _, checked ->
            persistToggle(isLocal = false, enabled = checked)
        }

        // Last run
        val lastRun = config.lastRunAt
        binding.tvLastRun.text = if (lastRun != null) {
            "Last backup: ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(lastRun))}"
        } else {
            "Last backup: never"
        }
    }

    // ── Local ─────────────────────────────────────────────────────────────────

    private fun onLocalTreePicked(uri: Uri) {
        contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        lifecycleScope.launch {
            val config = withContext(Dispatchers.IO) {
                repository.ensureBackupConfig(DeviceIdentity.defaultDeviceFolderName())
            }
            val updated = config.copy(localTreeUri = uri.toString(), localEnabled = true)
            withContext(Dispatchers.IO) { repository.saveBackupConfig(updated) }
            applyConfigToUi(updated)
        }
    }

    // ── Drive ─────────────────────────────────────────────────────────────────

    private fun connectDrive() {
        driveAuthLauncher.launch(Intent(this, DriveAuthActivity::class.java))
    }

    private fun onDriveConnected(email: String?) {
        lifecycleScope.launch {
            val config = withContext(Dispatchers.IO) {
                repository.ensureBackupConfig(DeviceIdentity.defaultDeviceFolderName())
            }
            val updated = config.copy(driveAccountEmail = email, driveEnabled = true)
            withContext(Dispatchers.IO) { repository.saveBackupConfig(updated) }
            applyConfigToUi(updated)
        }
    }

    private fun disconnectDrive() {
        DriveTokenStore.clear(this)
        lifecycleScope.launch {
            val config = withContext(Dispatchers.IO) {
                repository.ensureBackupConfig(DeviceIdentity.defaultDeviceFolderName())
            }
            val updated = config.copy(driveAccountEmail = null, driveEnabled = false)
            withContext(Dispatchers.IO) { repository.saveBackupConfig(updated) }
            applyConfigToUi(updated)
        }
    }

    // ── Shared ────────────────────────────────────────────────────────────────

    private fun persistToggle(isLocal: Boolean, enabled: Boolean) {
        lifecycleScope.launch {
            val config = withContext(Dispatchers.IO) {
                repository.ensureBackupConfig(DeviceIdentity.defaultDeviceFolderName())
            }
            val updated = if (isLocal) config.copy(localEnabled = enabled)
                          else config.copy(driveEnabled = enabled)
            withContext(Dispatchers.IO) { repository.saveBackupConfig(updated) }
        }
    }

    private fun saveDeviceName() {
        val raw = binding.etDeviceFolderName.text?.toString().orEmpty().trim()
        if (raw.isBlank()) {
            Toast.makeText(this, "Device folder name cannot be blank.", Toast.LENGTH_SHORT).show()
            return
        }
        val sanitized = raw.replace(Regex("[/\\\\:*?\"<>|]+"), "-").trim('-')
        if (sanitized.isBlank()) {
            Toast.makeText(this, "Device folder name contains only invalid characters.", Toast.LENGTH_SHORT).show()
            return
        }

        val imm = getSystemService(InputMethodManager::class.java)
        imm?.hideSoftInputFromWindow(binding.etDeviceFolderName.windowToken, 0)
        binding.etDeviceFolderName.clearFocus()

        lifecycleScope.launch {
            val config = withContext(Dispatchers.IO) {
                repository.ensureBackupConfig(DeviceIdentity.defaultDeviceFolderName())
            }
            val updated = config.copy(deviceFolderName = sanitized)
            withContext(Dispatchers.IO) { repository.saveBackupConfig(updated) }
            if (sanitized != raw) binding.etDeviceFolderName.setText(sanitized)
            Toast.makeText(this@BackupSettingsActivity, "Device folder name saved.", Toast.LENGTH_SHORT).show()
        }
    }
}
