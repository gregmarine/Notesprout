package com.notesprout.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Build
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.notesprout.android.databinding.ActivityMainBinding
import com.notesprout.android.databinding.DialogNewNotebookBinding
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    // API 29: request WRITE_EXTERNAL_STORAGE at runtime
    private val writeStorageLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            showNewNotebookDialog()
        } else {
            Toast.makeText(this, "Storage permission is required to create notebooks", Toast.LENGTH_LONG).show()
        }
    }

    // API 30+: send user to the All Files Access settings screen
    private val manageStorageLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) {
            showNewNotebookDialog()
        } else {
            Toast.makeText(this, "Storage access is required to create notebooks", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnNewNotebook.setOnClickListener {
            if (hasStoragePermission()) {
                showNewNotebookDialog()
            } else {
                requestStoragePermission()
            }
        }
    }

    private fun hasStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                }
                manageStorageLauncher.launch(intent)
            } catch (e: Exception) {
                // Fallback for devices that don't support the per-app intent
                val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                manageStorageLauncher.launch(intent)
            }
        } else {
            writeStorageLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    private fun showNewNotebookDialog() {
        val dialogBinding = DialogNewNotebookBinding.inflate(layoutInflater)
        val dialog = AlertDialog.Builder(this)
            .setTitle("New Notebook")
            .setView(dialogBinding.root)
            .setPositiveButton("Create") { _, _ ->
                val name = dialogBinding.editNotebookName.text?.toString()?.trim().orEmpty()
                if (name.isBlank()) {
                    Toast.makeText(this, "Notebook name cannot be empty", Toast.LENGTH_SHORT).show()
                } else {
                    createNotebook(name)
                }
            }
            .setNegativeButton("Cancel", null)
            .create()

        // Show keyboard as soon as the dialog window opens
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()

        // Flat style: no shadow, white fill + 1dp inkBlack border (same as shape_bordered)
        dialog.window?.setElevation(0f)
        dialog.window?.setBackgroundDrawableResource(R.drawable.shape_bordered)

        // Focus the input and open the keyboard. On API 30+ showSoftInput is effectively
        // deprecated — IME is controlled via WindowInsetsController on the view's own
        // window (the dialog window, not the activity window). A short delay lets the
        // dialog window fully take focus before the request lands.
        // Note: BOOX e-ink devices suppress the soft keyboard system-wide in favour of
        // pen input — the keyboard will not appear there, and that is expected behaviour.
        dialogBinding.editNotebookName.requestFocus()
        dialogBinding.editNotebookName.postDelayed({
            ViewCompat.getWindowInsetsController(dialogBinding.editNotebookName)
                ?.show(WindowInsetsCompat.Type.ime())
                ?: run {
                    // Fallback for API 29
                    val imm = getSystemService(InputMethodManager::class.java)
                    @Suppress("DEPRECATION")
                    imm.showSoftInput(dialogBinding.editNotebookName, InputMethodManager.SHOW_IMPLICIT)
                }
        }, 100)
    }

    private fun createNotebook(name: String) {
        try {
            // Ensure /Documents/NoteSprout/ exists
            val docsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
            val notesDir = File(docsDir, "NoteSprout")
            if (!notesDir.exists()) {
                check(notesDir.mkdirs()) { "Failed to create NoteSprout directory at ${notesDir.absolutePath}" }
            }

            val soilFile = File(notesDir, "$name.soil")

            // Open / create the SQLite database
            val db = SQLiteDatabase.openOrCreateDatabase(soilFile, null)
            try {
                // Configure database.
                // PRAGMAs that return result sets require rawQuery; moveToFirst() forces execution.
                db.rawQuery("PRAGMA journal_mode = WAL", null).use { it.moveToFirst() }
                db.rawQuery("PRAGMA wal_autocheckpoint = 100", null).use { it.moveToFirst() }
                db.rawQuery("PRAGMA auto_vacuum = INCREMENTAL", null).use { it.moveToFirst() }

                // Create the universal notebook table
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS notebook (
                        id          TEXT    PRIMARY KEY,
                        parentId    TEXT    NOT NULL,
                        boundingBox TEXT    NOT NULL,
                        "order"     INTEGER NOT NULL DEFAULT 0,
                        createdAt   INTEGER NOT NULL,
                        updatedAt   INTEGER NOT NULL,
                        deletedAt   INTEGER,
                        data        TEXT    NOT NULL
                    )
                    """.trimIndent()
                )

                // Create covering index for parent/order queries
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS idx_notebook_parent_order
                        ON notebook(parentId, "order", deletedAt)
                    """.trimIndent()
                )

                // Clean close: reclaim space and truncate WAL to zero bytes
                db.rawQuery("PRAGMA incremental_vacuum", null).use { it.moveToFirst() }
                db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
            } finally {
                db.close()
            }

            // Delete any 0-byte rollback journal left from database initialisation
            File("${soilFile.absolutePath}-journal").takeIf { it.exists() }?.delete()

            Toast.makeText(this, "Notebook '$name' created", Toast.LENGTH_SHORT).show()

        } catch (e: Exception) {
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
