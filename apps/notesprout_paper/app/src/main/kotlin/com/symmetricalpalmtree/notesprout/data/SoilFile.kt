package com.symmetricalpalmtree.notesprout.data

import android.content.Context
import java.io.File

/** The one directory that holds every notebook file. */
fun gardenDir(context: Context): File = File(context.getExternalFilesDir(null), "Garden")

/**
 * The single canonical way to derive a notebook's `.soil` path. **No other code constructs one.**
 * Flat directory, UUID filenames; folder structure lives exclusively in the global index.
 */
fun soilFile(context: Context, notebookId: String): File = File(gardenDir(context), "$notebookId.soil")

/** The global index file. */
fun indexFile(context: Context): File = File(context.getExternalFilesDir(null), "notesprout.db")

/** SQLite sidecars that may sit next to a database file (delete/move them with it). */
fun sidecarsOf(dbFile: File): List<File> =
    listOf("-wal", "-shm", "-journal").map { File(dbFile.path + it) }
