package com.notesprout.android.recognition.trocr

import android.content.Context
import android.net.Uri
import android.util.Log
import com.notesprout.android.core.Slog
import com.notesprout.android.recognition.HwrSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipInputStream

/**
 * Versioned store for TrOCR model bundles under `filesDir/hwr/models/<versionId>/`.
 *
 * Internal `filesDir` deliberately (not `getExternalFilesDir`): a personalized model's
 * weights encode the user's handwriting — biometric-adjacent, keep them app-private.
 *
 * Bundles are produced by `tools/hwr/make_bundle.py` and imported via SAF. Install is
 * atomic: stream to a temp dir, verify the manifest schema and every SHA-256, then move
 * into place — a failed install can never corrupt or remove the active model.
 */
class TrOcrModelStore(private val context: Context) {

    private val modelsDir: File get() = File(context.filesDir, "hwr/models")

    /** Directory of the active model, or null when none installed/active. */
    fun activeModelDir(): File? {
        val version = HwrSettings.activeModelVersion(context) ?: return null
        val dir = File(modelsDir, version)
        return if (File(dir, TrOcrManifest.FILE_MANIFEST).exists()) dir else null
    }

    fun activeManifest(): TrOcrManifest? = try {
        activeModelDir()?.let { dir ->
            TrOcrManifest.fromJson(File(dir, TrOcrManifest.FILE_MANIFEST).readText())
        }
    } catch (e: Exception) {
        Log.e(TAG, "Failed to read active manifest", e)
        null
    }

    fun listVersions(): List<TrOcrManifest> =
        modelsDir.listFiles()?.mapNotNull { dir ->
            try {
                val m = File(dir, TrOcrManifest.FILE_MANIFEST)
                if (m.exists()) TrOcrManifest.fromJson(m.readText()) else null
            } catch (_: Exception) { null }
        }?.sortedByDescending { it.createdAt } ?: emptyList()

    fun activate(versionId: String) {
        HwrSettings.setActiveModelVersion(context, versionId)
    }

    fun delete(versionId: String) {
        File(modelsDir, versionId).deleteRecursively()
        if (HwrSettings.activeModelVersion(context) == versionId) {
            // fall back to the newest remaining bundle, else none (Provider reverts to ML Kit)
            HwrSettings.setActiveModelVersion(context, listVersions().firstOrNull()?.versionId)
        }
    }

    /**
     * Import a bundle zip from a SAF [uri]. Verifies manifest schema, required files,
     * and per-file SHA-256 before atomically moving into place and activating.
     */
    suspend fun installFromUri(uri: Uri): Result<TrOcrManifest> = withContext(Dispatchers.IO) {
        val tempDir = File(context.filesDir, "hwr/tmp-${UUID.randomUUID()}")
        try {
            tempDir.mkdirs()

            context.contentResolver.openInputStream(uri)?.use { raw ->
                ZipInputStream(raw.buffered()).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        val name = File(entry.name).name // flatten — reject traversal by construction
                        if (!entry.isDirectory && name.isNotEmpty()) {
                            File(tempDir, name).outputStream().buffered().use { zip.copyTo(it) }
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            } ?: return@withContext Result.failure(IllegalStateException("Cannot open bundle"))

            val manifestFile = File(tempDir, TrOcrManifest.FILE_MANIFEST)
            if (!manifestFile.exists()) {
                return@withContext Result.failure(IllegalStateException("Bundle has no manifest.json"))
            }
            val manifest = TrOcrManifest.fromJson(manifestFile.readText())
            if (manifest.schema > TrOcrManifest.SUPPORTED_SCHEMA) {
                return@withContext Result.failure(
                    IllegalStateException("Bundle schema ${manifest.schema} is newer than this app supports")
                )
            }
            for (required in listOf(
                TrOcrManifest.FILE_ENCODER,
                TrOcrManifest.FILE_DECODER_INIT,
                TrOcrManifest.FILE_DECODER_PAST,
                TrOcrManifest.FILE_TOKENIZER,
            )) {
                if (manifest.files[required] == null || !File(tempDir, required).exists()) {
                    return@withContext Result.failure(IllegalStateException("Bundle missing $required"))
                }
            }
            for ((name, expectedSha) in manifest.files) {
                val f = File(tempDir, name)
                if (!f.exists()) {
                    return@withContext Result.failure(IllegalStateException("Bundle missing $name"))
                }
                val actual = sha256(f)
                if (!actual.equals(expectedSha, ignoreCase = true)) {
                    return@withContext Result.failure(IllegalStateException("Checksum mismatch for $name"))
                }
            }

            // Smoke decode: load the sessions from the temp dir and run one decode step on a
            // blank page. An incompatible or subtly corrupt model must fail HERE — after
            // activation it would take recognition down instead.
            try {
                TrOcrSession(tempDir, manifest).use { s ->
                    val n = manifest.imageSize * manifest.imageSize
                    val blank = java.nio.FloatBuffer.allocate(3 * n)
                    for (c in 0 until 3) {
                        val white = (1f - manifest.imageMean[c]) / manifest.imageStd[c]
                        for (i in 0 until n) blank.put(c * n + i, white)
                    }
                    s.generate(blank, maxNewTokens = 1)
                }
            } catch (e: Exception) {
                return@withContext Result.failure(IllegalStateException("Bundle failed smoke inference: ${e.message}", e))
            }

            modelsDir.mkdirs()
            val target = File(modelsDir, manifest.versionId)
            if (target.exists()) target.deleteRecursively() // reimport of same version replaces it
            if (!tempDir.renameTo(target)) {
                return@withContext Result.failure(IllegalStateException("Could not move bundle into place"))
            }
            activate(manifest.versionId)
            Slog.d(TAG) { "Installed model bundle ${manifest.versionId} (personalized=${manifest.personalized})" }
            Result.success(manifest)
        } catch (e: Exception) {
            Log.e(TAG, "Bundle install failed", e)
            Result.failure(e)
        } finally {
            if (tempDir.exists()) tempDir.deleteRecursively()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val TAG = "TrOcrModelStore"
    }
}
