package com.notesprout.android

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.notesprout.android.R
import com.notesprout.android.core.TopGuard
import com.notesprout.android.data.backup.BackupConfig
import com.notesprout.android.data.backup.BackupEngine
import com.notesprout.android.data.backup.BackupKind
import com.notesprout.android.data.backup.DeviceIdentity
import com.notesprout.android.data.backup.DriveApiClient
import com.notesprout.android.data.backup.DriveAuth
import com.notesprout.android.data.backup.DriveRestoreSource
import com.notesprout.android.data.backup.DriveTokenStore
import com.notesprout.android.data.backup.RestoreDevice
import com.notesprout.android.data.backup.RestoreEngine
import com.notesprout.android.data.backup.RestoreSource
import com.notesprout.android.data.backup.SafRestoreSource
import com.notesprout.android.data.index.IndexRepository
import com.notesprout.android.data.index.NotesproutIndex
import com.notesprout.android.databinding.ActivityBackupSettingsBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.atomic.AtomicBoolean

class BackupSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBackupSettingsBinding
    private val repository: IndexRepository by lazy { IndexRepository(NotesproutIndex.dao()) }
    private val isBackupRunning = AtomicBoolean(false)

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

    private val pickRestoreTreeLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) restoreFromSaf(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (bounceIfIndexNotReady()) return
        binding = ActivityBackupSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        TopGuard.applyInsetPadding(binding.root)

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

        binding.btnBackUpNow.setOnClickListener { startBackup() }
        binding.btnRestore.setOnClickListener { startRestore() }
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

        // Enable "Back Up Now" when at least one destination is ready
        val localReady = config.localEnabled && config.localTreeUri != null
        val driveReady = config.driveEnabled && config.driveAccountEmail != null
        binding.btnBackUpNow.isEnabled = localReady || driveReady
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

    // ── Backup run ────────────────────────────────────────────────────────────

    private fun startBackup() {
        if (!isBackupRunning.compareAndSet(false, true)) return

        val tvProgress = android.widget.TextView(this).apply {
            text = "Preparing…"
            setPadding(64, 48, 64, 48)
            setTextColor(android.graphics.Color.BLACK)
            textSize = 16f
        }
        val progressDialog = AlertDialog.Builder(this)
            .setTitle("Backing Up")
            .setView(tvProgress)
            .setCancelable(false)
            .create()
            .also { d ->
                d.show()
                d.window?.setElevation(0f)
                d.window?.setBackgroundDrawableResource(R.drawable.shape_dialog_bordered)
            }

        lifecycleScope.launch {
            val result = try {
                val config = withContext(Dispatchers.IO) {
                    repository.ensureBackupConfig(DeviceIdentity.defaultDeviceFolderName())
                }
                BackupEngine.run(
                    context = this@BackupSettingsActivity,
                    repo = repository,
                    config = config,
                    onProgress = { current, total, label ->
                        runOnUiThread {
                            tvProgress.text = "Backing up $current / $total\n$label"
                        }
                    }
                )
            } catch (e: Exception) {
                // The engine guards per-file copies but its own repo/index calls can still throw
                // (index sealed underneath, SQLite error) — that must end as a failed backup
                // message, not an app crash mid-backup.
                android.util.Log.e("BackupSettings", "backup run failed", e)
                Toast.makeText(this@BackupSettingsActivity, "Backup failed: ${e.message}", Toast.LENGTH_LONG).show()
                return@launch
            } finally {
                isBackupRunning.set(false)
                progressDialog.dismiss()
            }

            // Build summary message
            val sb = StringBuilder()
            result.perDestination.forEach { (kind, dest) ->
                val label = if (kind == BackupKind.LOCAL) "Local" else "Drive"
                if (dest.errors.isNotEmpty() && dest.attempted == 0) {
                    // Hard error — destination never became active
                    sb.appendLine("$label: ${dest.errors.first()}")
                } else {
                    sb.append("$label: ${dest.succeeded} backed up")
                    if (dest.failed > 0) sb.append(", ${dest.failed} failed")
                    if (dest.skipped > 0) sb.append(", ${dest.skipped} skipped")
                    if (dest.indexCopied) sb.append(", index copied") else sb.append(", index FAILED")
                    sb.appendLine()
                    dest.errors.forEach { sb.appendLine("  • $it") }
                }
            }
            if (result.perDestination.isEmpty()) sb.appendLine("No destinations enabled.")

            AlertDialog.Builder(this@BackupSettingsActivity)
                .setTitle("Backup Complete")
                .setMessage(sb.toString().trim())
                .setPositiveButton("OK", null)
                .create()
                .also { d ->
                    d.show()
                    d.window?.setElevation(0f)
                    d.window?.setBackgroundDrawableResource(R.drawable.shape_dialog_bordered)
                }

            refreshUi()
        }
    }

    // ── Restore ────────────────────────────────────────────────────────────────

    private fun startRestore() {
        AlertDialog.Builder(this)
            .setTitle("Restore from Backup")
            .setItems(arrayOf("Local folder", "Google Drive")) { _, which ->
                when (which) {
                    0 -> pickRestoreTreeLauncher.launch(null)
                    1 -> restoreFromDrive()
                }
            }
            .setNegativeButton("Cancel", null)
            .create().also { styleAndShow(it) }
    }

    private fun restoreFromSaf(uri: Uri) {
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        continueRestore(SafRestoreSource(this, uri), "local folder")
    }

    private fun restoreFromDrive() {
        lifecycleScope.launch {
            val tr = withContext(Dispatchers.IO) { DriveAuth.getAccessTokenSilent(this@BackupSettingsActivity) }
            when (tr) {
                is DriveAuth.TokenResult.Token ->
                    continueRestore(DriveRestoreSource(DriveApiClient(tr.accessToken)), "Google Drive")
                is DriveAuth.TokenResult.Error ->
                    Toast.makeText(this@BackupSettingsActivity, "Drive not connected: ${tr.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun continueRestore(source: RestoreSource, label: String) {
        lifecycleScope.launch {
            val progress = simpleProgress("Scanning $label…")
            val devices = try {
                withContext(Dispatchers.IO) { source.listDevices() }
            } catch (e: Exception) {
                emptyList()
            } finally {
                progress.dismiss()
            }
            if (devices.isEmpty()) {
                Toast.makeText(this@BackupSettingsActivity, "No backups found in $label.", Toast.LENGTH_LONG).show()
                return@launch
            }
            val names = devices.map {
                "${it.name} (${it.notebookCount} notebook${if (it.notebookCount == 1) "" else "s"})"
            }.toTypedArray()
            AlertDialog.Builder(this@BackupSettingsActivity)
                .setTitle("Choose a Backup")
                .setItems(names) { _, which -> confirmRestore(source, which, devices[which]) }
                .setNegativeButton("Cancel", null)
                .create().also { styleAndShow(it) }
        }
    }

    private fun confirmRestore(source: RestoreSource, index: Int, device: RestoreDevice) {
        AlertDialog.Builder(this)
            .setTitle("Replace your library?")
            .setMessage(
                "Restore \"${device.name}\" (${device.notebookCount} notebook" +
                "${if (device.notebookCount == 1) "" else "s"})?\n\n" +
                "This REPLACES the entire library on this device — everything currently here is removed. " +
                "You'll need that backup's recovery key to unlock it. This can't be undone."
            )
            .setPositiveButton("Restore") { _, _ -> runRestore(source, index) }
            .setNegativeButton("Cancel", null)
            .create().also { styleAndShow(it) }
    }

    private fun runRestore(source: RestoreSource, index: Int) {
        val tv = android.widget.TextView(this).apply {
            text = "Preparing…"; setPadding(64, 48, 64, 48)
            setTextColor(android.graphics.Color.BLACK); textSize = 16f
        }
        val progress = AlertDialog.Builder(this)
            .setTitle("Restoring").setView(tv).setCancelable(false)
            .create().also { styleAndShow(it) }
        lifecycleScope.launch {
            val result = try {
                RestoreEngine.restore(this@BackupSettingsActivity, source, index) { done, total ->
                    withContext(Dispatchers.Main) { tv.text = "Restoring $done / $total…" }
                }
            } finally {
                progress.dismiss()
            }
            when (result) {
                is RestoreEngine.Result.Success -> {
                    AlertDialog.Builder(this@BackupSettingsActivity)
                        .setTitle("Restore Complete")
                        .setMessage("Restored ${result.notebookCount} notebook${if (result.notebookCount == 1) "" else "s"}. The app will restart so you can unlock your library.")
                        .setCancelable(false)
                        .setPositiveButton("Restart") { _, _ -> restartApp() }
                        .create().also { styleAndShow(it) }
                }
                is RestoreEngine.Result.Failed ->
                    Toast.makeText(this@BackupSettingsActivity, "Restore failed: ${result.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun restartApp() {
        val i = packageManager.getLaunchIntentForPackage(packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        if (i != null) startActivity(i)
        Runtime.getRuntime().exit(0)
    }

    private fun simpleProgress(msg: String): AlertDialog {
        val tv = android.widget.TextView(this).apply {
            text = msg; setPadding(64, 48, 64, 48)
            setTextColor(android.graphics.Color.BLACK); textSize = 16f
        }
        return AlertDialog.Builder(this).setView(tv).setCancelable(false)
            .create().also { styleAndShow(it) }
    }

    private fun styleAndShow(d: AlertDialog) {
        d.show()
        d.window?.setElevation(0f)
        d.window?.setBackgroundDrawableResource(R.drawable.shape_dialog_bordered)
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
