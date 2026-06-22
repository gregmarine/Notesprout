package com.notesprout.android

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.notesprout.android.data.backup.BackupConfig
import com.notesprout.android.data.backup.DeviceIdentity
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
        if (uri != null) onTreePicked(uri, isLocal = true)
    }

    private val pickDriveTreeLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) onTreePicked(uri, isLocal = false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBackupSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnChooseLocal.setOnClickListener { pickLocalTreeLauncher.launch(null) }
        binding.btnChooseDrive.setOnClickListener { pickDriveTreeLauncher.launch(null) }

        binding.btnSaveDeviceName.setOnClickListener { saveDeviceName() }

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

        val driveUri = config.driveTreeUri?.let { Uri.parse(it) }
        binding.tvDriveStatus.text = if (driveUri != null) {
            driveUri.lastPathSegment?.substringAfterLast(':') ?: driveUri.toString()
        } else {
            "Not configured"
        }
        binding.switchDriveEnabled.isEnabled = driveUri != null
        binding.switchDriveEnabled.setOnCheckedChangeListener(null)
        binding.switchDriveEnabled.isChecked = config.driveEnabled && driveUri != null
        binding.switchDriveEnabled.setOnCheckedChangeListener { _, checked ->
            persistToggle(isLocal = false, enabled = checked)
        }

        val lastRun = config.lastRunAt
        binding.tvLastRun.text = if (lastRun != null) {
            "Last backup: ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(lastRun))}"
        } else {
            "Last backup: never"
        }
    }

    private fun onTreePicked(uri: Uri, isLocal: Boolean) {
        contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        lifecycleScope.launch {
            val config = withContext(Dispatchers.IO) {
                repository.ensureBackupConfig(DeviceIdentity.defaultDeviceFolderName())
            }
            val updated = if (isLocal) {
                config.copy(localTreeUri = uri.toString(), localEnabled = true)
            } else {
                config.copy(driveTreeUri = uri.toString(), driveEnabled = true)
            }
            withContext(Dispatchers.IO) { repository.saveBackupConfig(updated) }
            applyConfigToUi(updated)
        }
    }

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
