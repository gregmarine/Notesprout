package com.notesprout.android.data

import android.content.Context
import java.io.File

/**
 * Returns the canonical path for a notebook's .soil file in the flat Garden/ directory.
 * Creates the Garden/ directory if it doesn't exist yet.
 */
fun soilFile(context: Context, notebookId: String): File {
    val ext = context.getExternalFilesDir(null)
        ?: throw IllegalStateException("Device storage is unavailable — cannot locate the Garden directory")
    val garden = File(ext, "Garden")
    garden.mkdirs()
    return File(garden, "$notebookId.soil")
}
