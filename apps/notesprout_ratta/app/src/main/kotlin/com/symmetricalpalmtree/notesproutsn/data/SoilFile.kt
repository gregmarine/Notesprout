package com.symmetricalpalmtree.notesproutsn.data

import android.content.Context
import java.io.File

/** The one directory that holds every notebook file. */
fun gardenDir(context: Context): File = File(context.getExternalFilesDir(null), "Garden")

/**
 * The single canonical way to derive a notebook's `.soil` path. **No other code constructs one.**
 * Flat directory, UUID filenames; folder structure lives exclusively in the global index.
 */
fun soilFile(context: Context, notebookId: String): File = File(gardenDir(context), "$notebookId.soil")

/**
 * The staging sibling an import writes before it swaps a notebook into place (arc 16 / I1). It sits
 * in the **same directory** as the file it will become, which is the whole point: the last step of
 * an import is then a rename, not a copy, so a copy that dies half-way cannot leave a notebook the
 * user already had in pieces. Nothing enumerates `Garden/`, so a leftover is invisible to the
 * library; the next import over the same id clears it anyway.
 */
fun soilStagingFile(context: Context, notebookId: String): File =
    File(gardenDir(context), "$notebookId.soil.importing")

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
 * The single canonical way to derive an extension's host-owned store path: `Garden/<pkg>.db`,
 * beside the `.soil` files (arc 11 / J2). Nothing enumerates `Garden/` — the library's structure is
 * index-only — so the two kinds never mix and the `.db`s are invisible to it. Throws on a package
 * name that fails [isValidExtensionPackage]; a `..` or a `/` would otherwise escape the directory.
 */
fun extensionStoreFile(context: Context, pkg: String): File {
    require(isValidExtensionPackage(pkg)) { "not a valid extension package name" }
    return File(gardenDir(context), "$pkg.db")
}
