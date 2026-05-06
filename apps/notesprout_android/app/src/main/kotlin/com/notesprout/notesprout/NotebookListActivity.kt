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
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.notesprout.notesprout.data.NotebookMeta
import com.notesprout.notesprout.data.SoilDatabase
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class NotebookListActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "NotebookList"
        private const val NOTES_DIR = "/storage/emulated/0/Documents/NoteSprout"
        private const val SOIL_FILE = "notebook.soil"
    }

    data class NotebookEntry(val folderPath: String, val meta: NotebookMeta)

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: NotebookAdapter
    private val notebooks = mutableListOf<NotebookEntry>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
                "NoteSprout needs \"All files access\" to save your notebooks to " +
                "/storage/emulated/0/Documents/NoteSprout.\n\n" +
                "Tap OK to open Settings and grant the permission."
            )
            .setPositiveButton("Open Settings") { _, _ ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    try {
                        startActivity(Intent(
                            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:$packageName")
                        ))
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to open all-files-access settings", e)
                        startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun loadNotebooks() {
        val dir = File(NOTES_DIR)
        Log.i(TAG, "loadNotebooks: scanning $NOTES_DIR (exists=${dir.exists()}, canRead=${dir.canRead()})")
        if (!dir.exists()) {
            val created = dir.mkdirs()
            Log.i(TAG, "Created notes dir: $created")
        }

        val entries = mutableListOf<NotebookEntry>()
        dir.listFiles()?.filter { it.isDirectory }?.forEach { folder ->
            val soilFile = File(folder, SOIL_FILE)
            if (!soilFile.exists()) return@forEach
            val db = SoilDatabase(soilFile.absolutePath)
            try {
                db.open()
                val meta = db.getNotebookMeta() ?: run {
                    Log.w(TAG, "No meta in ${folder.name}")
                    return@forEach
                }
                entries.add(NotebookEntry(folder.absolutePath, meta))
            } catch (e: Exception) {
                Log.w(TAG, "Skipping corrupt notebook: ${folder.name}", e)
            } finally {
                db.close()
            }
        }

        Log.i(TAG, "loadNotebooks: found ${entries.size} notebooks")
        entries.sortByDescending { it.meta.updatedAt }
        notebooks.clear()
        notebooks.addAll(entries)
        adapter.notifyDataSetChanged()
    }

    private fun openNotebook(entry: NotebookEntry) {
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

        try {
            val folder = File(folderPath)
            val folderCreated = folder.mkdirs()
            Log.i(TAG, "mkdirs($folderPath) = $folderCreated, exists=${folder.exists()}, canWrite=${folder.canWrite()}")

            if (!folder.exists()) {
                Log.e(TAG, "Folder does not exist after mkdirs — aborting")
                showError("Could not create notebook folder. Check storage permission.")
                return
            }

            Log.i(TAG, "Opening SoilDatabase at $soilPath")
            val dm = resources.displayMetrics
            Log.i(TAG, "Page dimensions: ${dm.widthPixels}x${dm.heightPixels}")

            val db = SoilDatabase(soilPath)
            db.open()
            db.initializeNotebook(name, dm.widthPixels.toDouble(), dm.heightPixels.toDouble())
            db.close()
            Log.i(TAG, "createNotebook: success — soil file size=${File(soilPath).length()} bytes")

            loadNotebooks()
        } catch (e: Exception) {
            Log.e(TAG, "createNotebook FAILED for name=$name", e)
            showError("Failed to create notebook: ${e.message}")
        }
    }

    private fun showError(msg: String) {
        AlertDialog.Builder(this)
            .setTitle("Error")
            .setMessage(msg)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showContextMenu(entry: NotebookEntry) {
        AlertDialog.Builder(this)
            .setTitle(entry.meta.name)
            .setItems(arrayOf("Rename", "Delete")) { _, which ->
                when (which) {
                    0 -> showRenameDialog(entry)
                    1 -> showDeleteDialog(entry)
                }
            }
            .show()
    }

    private fun showRenameDialog(entry: NotebookEntry) {
        val input = EditText(this).apply {
            setText(entry.meta.name)
            setPadding(48, 24, 48, 8)
        }
        AlertDialog.Builder(this)
            .setTitle("Rename Notebook")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty() && newName != entry.meta.name) {
                    renameNotebook(entry, newName)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun renameNotebook(entry: NotebookEntry, newName: String) {
        try {
            val db = SoilDatabase("${entry.folderPath}/$SOIL_FILE")
            db.open()
            db.updateNotebookName(newName)
            db.close()
            File(entry.folderPath).renameTo(File("$NOTES_DIR/$newName"))
            Log.i(TAG, "Renamed ${entry.meta.name} -> $newName")
            loadNotebooks()
        } catch (e: Exception) {
            Log.e(TAG, "renameNotebook failed", e)
            showError("Rename failed: ${e.message}")
        }
    }

    private fun showDeleteDialog(entry: NotebookEntry) {
        AlertDialog.Builder(this)
            .setTitle("Delete Notebook")
            .setMessage("Delete \"${entry.meta.name}\"? This cannot be undone.")
            .setPositiveButton("Delete") { _, _ ->
                try {
                    File(entry.folderPath).deleteRecursively()
                    Log.i(TAG, "Deleted ${entry.folderPath}")
                    loadNotebooks()
                } catch (e: Exception) {
                    Log.e(TAG, "deleteNotebook failed", e)
                    showError("Delete failed: ${e.message}")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    inner class NotebookAdapter(
        private val items: List<NotebookEntry>,
        private val onClick: (NotebookEntry) -> Unit,
        private val onLongClick: (NotebookEntry) -> Unit
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
            holder.nameText.text = entry.meta.name
            holder.dateText.text = DateFormat.format("MMM dd, yyyy", entry.meta.updatedAt)
            holder.itemView.setOnClickListener { onClick(entry) }
            holder.itemView.setOnLongClickListener { onLongClick(entry); true }
        }

        override fun getItemCount() = items.size
    }
}
