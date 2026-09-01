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

/** What every extension store file is named with. */
const val EXTENSION_STORE_SUFFIX = ".db"

/** True iff [pkg] is a safe store-file stem: `[a-zA-Z0-9_.]+`, never a path segment or empty. */
fun isValidExtensionPackage(pkg: String): Boolean = EXTENSION_PACKAGE.matches(pkg)

/**
 * The single canonical way to derive an extension's host-owned store path: `Garden/<pkg>.db`,
 * beside the `.soil` files (arc 11 / J2). Throws on a package name that fails
 * [isValidExtensionPackage]; a `..` or a `/` would otherwise escape the directory.
 */
fun extensionStoreFile(context: Context, pkg: String): File {
    require(isValidExtensionPackage(pkg)) { "not a valid extension package name" }
    return File(gardenDir(context), "$pkg$EXTENSION_STORE_SUFFIX")
}

/**
 * The extension package a `Garden/` entry names a store for, or null when it names something else
 * — the pure half of [extensionStoreFiles], so the rule it applies is JVM-testable.
 *
 * The two kinds in the directory never collide: a notebook is `<uuid>.soil`, an import in flight is
 * `<uuid>.soil.importing`, and every sidecar carries its own suffix past the `.db`, so only a store
 * ends in [EXTENSION_STORE_SUFFIX]. The stem must still pass [isValidExtensionPackage] — anything
 * else in there is not something this app wrote, and a backup copies what it can name.
 */
fun extensionStorePackage(fileName: String): String? {
    if (!fileName.endsWith(EXTENSION_STORE_SUFFIX)) return null
    val stem = fileName.dropLast(EXTENSION_STORE_SUFFIX.length)
    return stem.takeIf(::isValidExtensionPackage)
}

/**
 * Every extension store on the device, in a stable order (arc 21 / W5 — the backup set grew to
 * cover them). **This is the one place `Garden/` is enumerated**: the library's structure is
 * index-only and stays that way, but an extension store has no index row to be listed from — the
 * host mints one the first time an extension is lent its store and the file is the only record
 * that it exists. A store outlives its extension on purpose (arc 11: removing an extension's data
 * is a deliberate act), so an uninstalled extension's store is still backed up.
 */
fun extensionStoreFiles(context: Context): List<File> =
    (gardenDir(context).listFiles() ?: emptyArray())
        .filter { it.isFile && extensionStorePackage(it.name) != null }
        .sortedBy { it.name }
