package com.symmetricalpalmtree.notesproutsn.ext.bible

import android.content.Context
import java.io.File
import java.io.FileOutputStream

/**
 * Copies the bundled read-only `.bible` source out of the APK's assets into the
 * extension's own storage, where SQLite can open it by path. (An asset inside
 * the APK has no real file path, so it must be materialised first.)
 *
 * **This is the one sanctioned "extension writes to disk" exception**
 * (`BIBLE_PLAN.md` decision 4, the ML Kit model class): what it writes is
 * re-derivable APK content, **never user data**. It lands in
 * [Context.getNoBackupFilesDir] — not `filesDir` — so the platform's backup
 * agent never ships 12 MB of public-domain scripture anywhere, and an uninstall
 * takes it with the package. Everything the *user* produces still lives in the
 * host's extension store, because an extension writes no user data itself, ever.
 *
 * Blocking: call it on `Dispatchers.IO`. Ported from Biblesprout
 * (`data/ContentInstaller.kt`), with the destination moved and the copy made
 * crash-safe (`.part` → fsync → rename, and the stamp written last).
 */
class ContentInstaller(private val context: Context) {

    /** Where the installed source lives; created on first use. */
    val contentDir: File by lazy {
        File(context.noBackupFilesDir, DIR).apply { mkdirs() }
    }

    /**
     * Ensures [assetPath] (e.g. `bible/bsb.bible`) is present in [contentDir] as
     * [destName], copying it if missing or if the bundled asset has changed
     * since the installed copy was written. Returns the installed file.
     *
     * The copy goes to `<destName>.part`, is fsynced, and is only then renamed
     * over the destination — a power loss mid-copy leaves the old file (or no
     * file), never a half one. The stamp is written **after** the rename and
     * deleted **before** the copy, so a torn install can never look complete.
     */
    fun ensureInstalled(assetPath: String, destName: String): File {
        val dest = File(contentDir, destName)
        val stampFile = File(contentDir, "$destName.stamp")
        val stamp = bundledStamp(assetPath)
        val installed = runCatching { stampFile.readText() }.getOrNull()
        if (dest.exists() && installed == stamp) return dest

        runCatching { stampFile.delete() }
        val part = File(contentDir, "$destName.part")
        context.assets.open(assetPath).use { input ->
            FileOutputStream(part).use { output ->
                input.copyTo(output)
                output.flush()
                output.fd.sync()
            }
        }
        if (!part.renameTo(dest)) {
            dest.delete()
            check(part.renameTo(dest)) { "could not install $destName" }
        }
        runCatching { stampFile.writeText(stamp) }
        return dest
    }

    /**
     * Identity of the bundled asset: when this APK was installed, plus the
     * asset's size.
     *
     * Size alone is **not** enough. Rebuilding a database can change its
     * contents without changing its length — SQLite pads to whole pages, so a
     * fix worth a few dozen characters lands on exactly the same byte count —
     * and the stale copy then survives on device, silently serving old content.
     * The install time changes on every update, which is the only way bundled
     * content can change.
     */
    private fun bundledStamp(assetPath: String): String {
        val updated = try {
            context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        } catch (_: Exception) {
            0L
        }
        return "$updated:${bundledSize(assetPath)}"
    }

    /** Uncompressed size of a bundled asset, or -1 if it can't be determined. */
    private fun bundledSize(assetPath: String): Long =
        try {
            context.assets.openFd(assetPath).use { it.length }
        } catch (_: Exception) {
            // Thrown when the asset is stored compressed; fall back to
            // copy-if-missing (see `noCompress "bible"` in build.gradle.kts).
            -1L
        }

    companion object {
        /** The subdirectory of `noBackupFilesDir` the source is installed into. */
        const val DIR = "bible"

        /** The bundled Berean Standard Bible: asset path and installed name. */
        const val BSB_ASSET = "bible/bsb.bible"
        const val BSB_NAME = "bsb.bible"
    }
}
