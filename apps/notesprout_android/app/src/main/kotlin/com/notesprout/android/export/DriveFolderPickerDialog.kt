package com.notesprout.android.export

import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.notesprout.android.R
import com.notesprout.android.data.backup.DriveApiClient
import com.notesprout.android.data.backup.DriveAuth
import com.notesprout.android.data.backup.ROOT_EXPORT_FOLDER
import com.notesprout.android.databinding.DialogDriveFolderPickerBinding
import com.notesprout.android.databinding.DialogDriveNewFolderBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Folder picker for the export screen's Google Drive destination.
 *
 * Browses the app-owned "Notesprout Exports" tree only — the `drive.file` OAuth scope cannot see
 * folders the app didn't create, so there is nothing else to show. The result is a list of path
 * *names* under that root, not Drive ids: nothing is created remotely while browsing. "New
 * folder…" just descends into a not-yet-existing name (its listing is empty), and the whole chain
 * is find-or-created at upload time. Backing out of an export therefore never leaves empty
 * folders behind on Drive.
 *
 * The caller is responsible for ensuring a Drive connection exists before showing this; a missing
 * or expired token surfaces here as an inline error message rather than a crash.
 */
class DriveFolderPickerDialog(
    private val activity: AppCompatActivity,
    initialPath: List<String>,
    private val onChosen: (List<String>) -> Unit,
) {
    private val path = initialPath.toMutableList()
    private lateinit var binding: DialogDriveFolderPickerBinding
    private var dialog: AlertDialog? = null
    private var loadJob: Job? = null

    /** The access token, fetched once on first listing and reused for the dialog's lifetime. */
    private var client: DriveApiClient? = null

    fun show() {
        binding = DialogDriveFolderPickerBinding.inflate(activity.layoutInflater)
        val d = AlertDialog.Builder(activity)
            .setTitle("Google Drive folder")
            .setView(binding.root)
            .setPositiveButton("Use this folder") { _, _ -> onChosen(path.toList()) }
            .setNegativeButton("Cancel", null)
            .create()
        d.setOnDismissListener { loadJob?.cancel() }
        d.show()
        d.window?.setElevation(0f)
        d.window?.setBackgroundDrawableResource(R.drawable.shape_dialog_bordered)
        dialog = d
        refresh()
    }

    // ── Listing ──────────────────────────────────────────────────────────────

    private sealed interface Listing {
        data class Folders(val names: List<String>) : Listing
        data class Error(val message: String) : Listing
    }

    private fun refresh() {
        binding.tvPickerPath.text = (listOf(ROOT_EXPORT_FOLDER) + path).joinToString(" / ")
        binding.tvPickerStatus.isVisible = true
        binding.tvPickerStatus.text = "Loading…"
        binding.folderRows.removeAllViews()
        loadJob?.cancel()
        loadJob = activity.lifecycleScope.launch {
            val listing = withContext(Dispatchers.IO) { listCurrent() }
            if (dialog?.isShowing != true) return@launch
            when (listing) {
                is Listing.Error -> binding.tvPickerStatus.text = listing.message
                is Listing.Folders -> {
                    binding.tvPickerStatus.isVisible = false
                    renderRows(listing.names)
                }
            }
        }
    }

    /** Resolve the current path and list its subfolders. Call on IO. */
    private suspend fun listCurrent(): Listing {
        val c = client ?: when (val t = DriveAuth.getAccessTokenSilent(activity)) {
            is DriveAuth.TokenResult.Error -> return Listing.Error(t.message)
            is DriveAuth.TokenResult.Token -> DriveApiClient(t.accessToken).also { client = it }
        }
        return try {
            // Walk by name from My Drive. A segment that doesn't exist yet (root included) is a
            // "virtual" folder — nothing was created while browsing — and simply lists empty.
            var id = c.findChild(ROOT_EXPORT_FOLDER, "root", foldersOnly = true)
                ?: return Listing.Folders(emptyList())
            for (segment in path) {
                id = c.findChild(segment, id, foldersOnly = true)
                    ?: return Listing.Folders(emptyList())
            }
            Listing.Folders(c.listChildren(id, foldersOnly = true).map { it.name }
                .sortedBy { it.lowercase() })
        } catch (e: Exception) {
            Listing.Error(e.message ?: "Couldn't list the folder.")
        }
    }

    // ── Rows ─────────────────────────────────────────────────────────────────

    private fun renderRows(names: List<String>) {
        val container = binding.folderRows
        container.removeAllViews()
        if (path.isNotEmpty()) {
            container.addView(row("← Up") {
                path.removeAt(path.size - 1)
                refresh()
            })
        }
        for (name in names) {
            container.addView(row(name) {
                path.add(name)
                refresh()
            })
        }
        container.addView(row("New folder…") { promptNewFolder() })
    }

    private fun row(label: String, onTap: () -> Unit): TextView {
        val v = activity.layoutInflater
            .inflate(R.layout.item_export_preset, binding.folderRows, false) as TextView
        v.text = label
        v.setOnClickListener { onTap() }
        return v
    }

    // ── New folder ───────────────────────────────────────────────────────────

    private fun promptNewFolder() {
        val nameBinding = DialogDriveNewFolderBinding.inflate(activity.layoutInflater)
        val d = AlertDialog.Builder(activity)
            .setTitle("New folder")
            .setPositiveButton("Create") { _, _ ->
                hideIme(nameBinding.editFolderName)
                val name = nameBinding.editFolderName.text?.toString()?.trim().orEmpty()
                if (name.isEmpty()) {
                    Toast.makeText(activity, "Folder needs a name", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                path.add(name)
                refresh()
            }
            .setNegativeButton("Cancel") { _, _ -> hideIme(nameBinding.editFolderName) }
            .setView(nameBinding.root)
            .create()
        d.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        d.show()
        d.window?.setElevation(0f)
        d.window?.setBackgroundDrawableResource(R.drawable.shape_dialog_bordered)
        nameBinding.editFolderName.requestFocus()
    }

    /** BOOX keeps the IME up unless it is dismissed explicitly — see docs/design-system.md. */
    private fun hideIme(view: android.view.View) {
        activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(view.windowToken, 0)
    }
}
