package com.notesprout.android.data

import android.content.Context
import java.io.File

/**
 * Returns the canonical path for a notebook's .soil file in the flat Garden/ directory.
 * Creates the Garden/ directory if it doesn't exist yet.
 */
fun soilFile(context: Context, notebookId: String): File {
    val garden = File(context.getExternalFilesDir(null)!!, "Garden")
    garden.mkdirs()
    return File(garden, "$notebookId.soil")
}
