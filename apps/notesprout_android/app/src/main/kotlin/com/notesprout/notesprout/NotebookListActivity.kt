package com.notesprout.notesprout

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.text.format.DateFormat
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.notesprout.notesprout.data.NotebookRegistry
import com.notesprout.notesprout.data.RegistryEntry
import com.notesprout.notesprout.data.SoilDatabase
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class NotebookListActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "NotebookList"
        private const val NOTES_DIR = "/storage/emulated/0/Documents/NoteSprout"
        private const val SOIL_FILE = "notebook.soil"
    }

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: NotebookAdapter
    private lateinit var registry: NotebookRegistry
    private val notebooks = mutableListOf<RegistryEntry>()
    private var skippedWarningShown = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        setContentView(R.layout.activity_notebook_list)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
        }

        val rootLayout = findViewById<View>(R.id.rootLayout)
        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        registry = NotebookRegistry(File("$NOTES_DIR/registry.json"))

        recyclerView = findViewById(R.id.recyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.addItemDecoration(DividerItemDecoration(this, DividerItemDecoration.VERTICAL))
        adapter = NotebookAdapter(
            notebooks,
            onClick = { openNotebook(it) },
            onLongClick = { showContextMenu(it) }
        )
        recyclerView.adapter = adapter

        findViewById<FloatingActionButton>(R.id.fab).setOnClickListener {
            if (!hasStoragePermission()) {
                showPermissionDialog()
            } else {
                showCreateDialog()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!hasStoragePermission()) {
            showPermissionDialog()
            return
        }
        loadNotebooks()
    }

    private fun hasStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }
    }

    private fun showPermissionDialog() {
        AlertDialog.Builder(this)
            .setTitle("Storage Permission Required")
            .setMessage(
                "NoteSprout needs full storage access to save and manage your notebooks in the " +
                "Documents folder. This allows you to access your notebooks from file managers " +
                "and back them up."
            )
            .setCancelable(false)
            .setPositiveButton("Open Settings") { _, _ ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    try {
                        startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                Uri.parse("package:$packageName")
                            )
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to open all-files-access settings", e)
                        startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                    }
                }
            }
            .setNegativeButton("Cancel") { _, _ ->
                Toast.makeText(this, "NoteSprout requires storage access to function", Toast.LENGTH_LONG).show()
                finish()
            }
            .show()
    }

    private fun loadNotebooks() {
        val dir = File(NOTES_DIR)
        Log.i(TAG, "loadNotebooks: dir=$NOTES_DIR exists=${dir.exists()}")
        if (!dir.exists()) dir.mkdirs()

        val templatesDir = File("$NOTES_DIR/Templates")
        if (!templatesDir.exists()) {
            templatesDir.mkdirs()
            Log.i(TAG, "Templates folder created")
        } else {
            Log.i(TAG, "Templates folder already exists")
        }

        Thread {
            val result = try {
                registry.reconcile(dir)
            } catch (e: Exception) {
                Log.e(TAG, "loadNotebooks: reconcile failed", e)
                return@Thread
            }

            if (result.orphansAdopted > 0) {
                Log.i(TAG, "loadNotebooks: adopted ${result.orphansAdopted} orphan notebooks")
            }
            if (result.missingRemoved > 0) {
                Log.i(TAG, "loadNotebooks: removed ${result.missingRemoved} missing registry entries")
            }

            val sorted = result.entries.sortedByDescending { it.updatedAt }
            Log.i(TAG, "loadNotebooks: found ${sorted.size} notebooks")

            runOnUiThread {
                notebooks.clear()
                notebooks.addAll(sorted)
                adapter.notifyDataSetChanged()

                if (result.skippedCount > 0 && !skippedWarningShown) {
                    skippedWarningShown = true
                    Toast.makeText(this, "Some notebooks could not be read", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun openNotebook(entry: RegistryEntry) {
        Log.i(TAG, "openNotebook: ${entry.folderPath}")
        val intent = Intent(this, CanvasActivity::class.java).apply {
            putExtra(CanvasActivity.EXTRA_FOLDER_PATH, entry.folderPath)
        }
        startActivity(intent)
    }

    private fun showCreateDialog() {
        val nameSuggestion = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val input = EditText(this).apply {
            setText(nameSuggestion)
            setPadding(48, 24, 48, 8)
        }
        AlertDialog.Builder(this)
            .setTitle("New Notebook")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) createNotebook(name)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun createNotebook(name: String) {
        val folderPath = "$NOTES_DIR/$name"
        val soilPath = "$folderPath/$SOIL_FILE"
        Log.i(TAG, "createNotebook: name=$name folderPath=$folderPath")

        Thread {
            try {
                val folder = File(folderPath)
                val folderCreated = folder.mkdirs()
                Log.i(TAG, "createNotebook: mkdirs=$folderCreated exists=${folder.exists()} canWrite=${folder.canWrite()}")

                if (!folder.exists()) {
                    Log.e(TAG, "createNotebook: folder absent after mkdirs — aborting")
                    runOnUiThread { showError("Could not create notebook folder. Check storage permission.") }
                    return@Thread
                }

                val dm = resources.displayMetrics
                Log.i(TAG, "createNotebook: opening DB at $soilPath, page=${dm.widthPixels}x${dm.heightPixels}")

                val db = SoilDatabase(soilPath)
                db.open()
                db.initializeNotebook(name, dm.widthPixels.toDouble(), dm.heightPixels.toDouble())
                val meta = db.getNotebookMeta()
                val firstPage = db.getFirstPage()
                db.close()

                Log.i(TAG, "createNotebook: success, soil size=${File(soilPath).length()} bytes firstPageId=${firstPage?.id}")

                val now = System.currentTimeMillis()
                val entry = RegistryEntry(
                    id = meta?.id ?: UUID.randomUUID().toString(),
                    folderPath = folderPath,
                    name = name,
                    createdAt = meta?.createdAt ?: now,
                    updatedAt = meta?.updatedAt ?: now
                )
                registry.add(entry)
                runOnUiThread {
                    val intent = Intent(this@NotebookListActivity, CanvasActivity::class.java).apply {
                        putExtra(CanvasActivity.EXTRA_FOLDER_PATH, folderPath)
                    }
                    startActivity(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "createNotebook FAILED for name=$name", e)
                runOnUiThread { showError("Failed to create notebook: ${e.message}") }
            }
        }.start()
    }

    private fun showError(msg: String) {
        AlertDialog.Builder(this)
            .setTitle("Error")
            .setMessage(msg)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showContextMenu(entry: RegistryEntry) {
        AlertDialog.Builder(this)
            .setTitle(entry.name)
            .setItems(arrayOf("Rename", "Delete")) { _, which ->
                when (which) {
                    0 -> showRenameDialog(entry)
                    1 -> showDeleteDialog(entry)
                }
            }
            .show()
    }

    private fun showRenameDialog(entry: RegistryEntry) {
        val input = EditText(this).apply {
            setText(entry.name)
            setPadding(48, 24, 48, 8)
        }
        AlertDialog.Builder(this)
            .setTitle("Rename Notebook")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty() && newName != entry.name) {
                    renameNotebook(entry, newName)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun renameNotebook(entry: RegistryEntry, newName: String) {
        Thread {
            try {
                val db = SoilDatabase("${entry.folderPath}/$SOIL_FILE")
                db.open()
                db.updateNotebookName(newName)
                db.close()

                val newFolderPath = "$NOTES_DIR/$newName"
                val renamed = File(entry.folderPath).renameTo(File(newFolderPath))
                Log.i(TAG, "renameNotebook: ${entry.name} -> $newName, folderRenamed=$renamed")

                registry.remove(entry.folderPath)
                registry.add(
                    entry.copy(
                        folderPath = newFolderPath,
                        name = newName,
                        updatedAt = System.currentTimeMillis()
                    )
                )
                loadNotebooks()
            } catch (e: Exception) {
                Log.e(TAG, "renameNotebook failed", e)
                runOnUiThread { showError("Rename failed: ${e.message}") }
            }
        }.start()
    }

    private fun showDeleteDialog(entry: RegistryEntry) {
        AlertDialog.Builder(this)
            .setTitle("Delete Notebook")
            .setMessage("Delete \"${entry.name}\"? This cannot be undone.")
            .setPositiveButton("Delete") { _, _ -> deleteNotebook(entry) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteNotebook(entry: RegistryEntry) {
        Thread {
            Log.i(TAG, "deleteNotebook: starting delete of ${entry.folderPath}")

            // Step 1: Remove from registry before touching disk
            registry.remove(entry.folderPath)

            // Step 2: Open and immediately close the DB to flush WAL checkpoint
            val soilPath = "${entry.folderPath}/$SOIL_FILE"
            try {
                val db = SoilDatabase(soilPath)
                db.open()
                db.close()
                Log.i(TAG, "deleteNotebook: WAL flushed for $soilPath")
            } catch (e: Exception) {
                Log.w(TAG, "deleteNotebook: could not flush database before delete: ${e.message}")
            }

            // Step 3: Explicitly delete WAL/SHM/journal companion files
            val soilFile = File(soilPath)
            for (suffix in listOf("-wal", "-shm", "-journal")) {
                val companion = File(soilFile.path + suffix)
                if (companion.exists()) {
                    val ok = companion.delete()
                    Log.i(TAG, "deleteNotebook: delete $suffix ok=$ok path=${companion.path}")
                }
            }

            // Step 4: Delete the folder recursively
            val folder = File(entry.folderPath)
            val deleted = folder.deleteRecursively()
            Log.i(TAG, "deleteNotebook: folder=${entry.folderPath} deleted=$deleted exists=${folder.exists()}")

            if (!deleted || folder.exists()) {
                Log.e(TAG, "deleteNotebook: FAILED to delete folder ${entry.folderPath}")
                runOnUiThread {
                    Toast.makeText(this, "Could not delete notebook. Please try again.", Toast.LENGTH_SHORT).show()
                }
            }

            loadNotebooks()
        }.start()
    }

    inner class NotebookAdapter(
        private val items: List<RegistryEntry>,
        private val onClick: (RegistryEntry) -> Unit,
        private val onLongClick: (RegistryEntry) -> Unit
    ) : RecyclerView.Adapter<NotebookAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val nameText: TextView = view.findViewById(R.id.notebookName)
            val dateText: TextView = view.findViewById(R.id.notebookDate)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_notebook, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val entry = items[position]
            holder.nameText.text = entry.name
            holder.dateText.text = DateFormat.format("MMM dd, yyyy", entry.updatedAt)
            holder.itemView.setOnClickListener { onClick(entry) }
            holder.itemView.setOnLongClickListener { onLongClick(entry); true }
        }

        override fun getItemCount() = items.size
    }
}
