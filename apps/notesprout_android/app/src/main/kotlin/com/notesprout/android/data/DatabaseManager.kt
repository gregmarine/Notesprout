package com.notesprout.android.data

import android.content.Context
import android.os.Environment
import java.io.File

class DatabaseManager(private val context: Context) {

    val noteSproutDir: File = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
        "NoteSprout"
    )

    private var currentDatabase: SoilDatabase? = null

    init {
        ensureDirectoryExists()
    }

    private fun ensureDirectoryExists() {
        if (!noteSproutDir.exists()) noteSproutDir.mkdirs()
    }

    fun createNotebook(notebookName: String): SoilDatabase {
        closeCurrentDatabase()
        val fileName = sanitizeName(notebookName) + ".soil"
        val file = File(noteSproutDir, fileName)
        return SoilDatabase(context, file.absolutePath).also { currentDatabase = it }
    }

    fun openNotebook(filePath: String): SoilDatabase {
        closeCurrentDatabase()
        return SoilDatabase(context, filePath).also { currentDatabase = it }
    }

    fun listNotebooks(): List<File> =
        noteSproutDir.listFiles { file -> file.extension == "soil" }?.toList() ?: emptyList()

    fun closeCurrentDatabase() {
        currentDatabase?.close()
        currentDatabase = null
    }

    private fun sanitizeName(name: String): String =
        name.replace(Regex("[^A-Za-z0-9 \\-]"), "").trim().ifEmpty { "Untitled" }
}
