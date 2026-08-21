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

/** Package names an extension store may be keyed by (a `ProviderRef` package name — never user input). */
private val EXTENSION_PACKAGE = Regex("[a-zA-Z0-9_.]+")

/** True iff [pkg] is a safe store-file stem: `[a-zA-Z0-9_.]+`, never a path segment or empty. */
fun isValidExtensionPackage(pkg: String): Boolean = EXTENSION_PACKAGE.matches(pkg)

/**
 * The single canonical way to derive an extension's host-owned store path: `Garden/<pkg>.db`, beside
 * the `.soil` files (nothing enumerates `Garden/`, so the two kinds never mix). Throws on a package
 * name that fails [isValidExtensionPackage].
 */
fun extensionStoreFile(context: Context, pkg: String): File {
    require(isValidExtensionPackage(pkg)) { "not a valid extension package name" }
    return File(gardenDir(context), "$pkg.db")
}
