package com.notesprout.android.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.notesprout.android.R
import com.notesprout.android.data.DatabaseManager
import com.notesprout.android.plugins.PluginEngine
import com.notesprout.android.plugins.PluginRegistry
import com.notesprout.android.plugins.structural.NotebookManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var pluginEngine: PluginEngine
    private lateinit var databaseManager: DatabaseManager

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.any { !it }) showStoragePermissionDialog()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        checkStoragePermission()

        databaseManager = DatabaseManager(this)

        // database = null: no notebook open yet; DataApi logs warnings if called before open.
        pluginEngine = PluginEngine(this, database = null)
        lifecycleScope.launch(Dispatchers.IO) {
            pluginEngine.initializeRuntime()
            PluginRegistry.initialize(applicationContext, pluginEngine)
            Log.d("NoteSprout", "plugin engine ready")

            val notebookManager = NotebookManager(databaseManager, pluginEngine)

            if (databaseManager.listNotebooks().isEmpty()) {
                Log.d("NoteSprout", "no notebooks found — creating Test Notebook")
                notebookManager.createNotebook("Test Notebook", 1404.0, 1872.0)
            }

            val notebookFiles = databaseManager.listNotebooks()
            val notebookObj = if (notebookFiles.isNotEmpty()) {
                notebookManager.openNotebook(notebookFiles.first().absolutePath)
            } else null

            val notebookName = notebookObj?.let {
                JSONObject(it.data).optString("name", "Unknown")
            } ?: "none"
            val notebookId = notebookObj?.id ?: "none"
            Log.d("NoteSprout", "notebook: $notebookName  id=$notebookId")

            val pages = notebookObj?.let { notebookManager.getPages(it.id) } ?: emptyList()
            Log.d("NoteSprout", "pages: ${pages.size}")

            val layers = if (pages.isNotEmpty()) {
                notebookManager.getLayers(pages.first().id)
            } else emptyList()
            Log.d("NoteSprout", "layers on first page: ${layers.size}")

            val displayText = "NoteSprout\n$notebookName\n${pages.size} page(s)"
            withContext(Dispatchers.Main) {
                findViewById<TextView>(R.id.statusText).text = displayText
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::pluginEngine.isInitialized) pluginEngine.destroy()
    }

    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) showStoragePermissionDialog()
        }
    }

    private fun checkStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) showStoragePermissionDialog()
        } else {
            val writeGranted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
            if (!writeGranted) {
                requestPermissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.READ_EXTERNAL_STORAGE,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                    )
                )
            }
        }
    }

    private fun showStoragePermissionDialog() {
        AlertDialog.Builder(this)
            .setTitle("Storage Permission Required")
            .setMessage(
                "NoteSprout needs storage access to save your notebooks. " +
                "Please grant storage permission to continue."
            )
            .setPositiveButton("Open Settings") { _, _ ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    startActivity(
                        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                            data = Uri.fromParts("package", packageName, null)
                        }
                    )
                } else {
                    startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", packageName, null)
                        }
                    )
                }
            }
            .setNegativeButton("Dismiss", null)
            .show()
    }
}
