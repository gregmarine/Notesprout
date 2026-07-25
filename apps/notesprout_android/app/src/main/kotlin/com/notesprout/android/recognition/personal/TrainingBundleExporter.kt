package com.notesprout.android.recognition.personal

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import com.notesprout.android.recognition.trocr.LineRasterizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Exports the confirmed training pairs as a zip for the Mac fine-tune loop
 * (`tools/hwr/finetune.py`). Layout:
 *
 *   meta.json            — app/rasterizer versions, counts, export time
 *   labels.jsonl         — one JSON object per pair: id, label, source, originalText?, …
 *   pairs/<id>.png       — the line rendered by the SAME LineRasterizer used at inference
 *                          (train/infer match is the critical correctness property)
 *   strokes/<id>.json    — raw LiveStroke list so training can re-rasterize with augmentation
 *
 * The user picks the destination via SAF — nothing is written outside their chosen file,
 * and nothing leaves the device unless they move the file themselves.
 */
object TrainingBundleExporter {

    private const val TAG = "TrainingBundleExport"

    /** Bump when LineRasterizer's rendering changes — finetune.py validates against it. */
    const val RASTERIZER_VERSION = 1

    const val BUNDLE_SCHEMA = 1

    @Serializable
    private data class LabelRow(
        val id: String,
        val label: String,
        val source: String,
        val originalText: String? = null,
        val refLineHeight: Float = 0f,
        val createdAt: Long,
    )

    @Serializable
    private data class Meta(
        val schema: Int,
        val rasterizerVersion: Int,
        val appVersionName: String,
        val exportedAt: Long,
        val pairCount: Int,
    )

    private val json = Json { ignoreUnknownKeys = true }

    /** Suggested SAF file name. */
    fun suggestedName(): String =
        "notesprout-hwr-train-" + java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
            .format(java.util.Date()) + ".zip"

    /**
     * Write all confirmed pairs to [uri]. Returns the number of pairs exported.
     * The rasterizer's imageSize/mean/std are irrelevant here (only stage-1
     * `renderLineBitmap` is used), so a fixed 384/0.5 instance is fine.
     */
    suspend fun export(context: Context, uri: Uri): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val pairs = TrainingPairRepository.confirmedPairs(context)
            if (pairs.isEmpty()) return@withContext Result.success(0)

            val raster = LineRasterizer(384, floatArrayOf(0.5f, 0.5f, 0.5f), floatArrayOf(0.5f, 0.5f, 0.5f))
            val versionName = try {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
            } catch (_: Exception) { "?" }

            val out = context.contentResolver.openOutputStream(uri)
                ?: return@withContext Result.failure(IllegalStateException("Cannot open destination"))

            var exported = 0
            ZipOutputStream(out.buffered()).use { zip ->
                val labels = StringBuilder()
                for (pair in pairs) {
                    val strokes = TrainingPairRepository.decodeStrokes(pair)
                    if (strokes.isEmpty()) continue

                    zip.putNextEntry(ZipEntry("pairs/${pair.id}.png"))
                    val bitmap = raster.renderLineBitmap(strokes)
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, zip)
                    bitmap.recycle()
                    zip.closeEntry()

                    zip.putNextEntry(ZipEntry("strokes/${pair.id}.json"))
                    zip.write(pair.strokesJson.toByteArray())
                    zip.closeEntry()

                    labels.append(
                        json.encodeToString(
                            LabelRow.serializer(),
                            LabelRow(
                                id = pair.id,
                                label = pair.label,
                                source = pair.source,
                                originalText = pair.originalText,
                                refLineHeight = pair.refLineHeight,
                                createdAt = pair.createdAt,
                            ),
                        )
                    ).append('\n')
                    exported++
                }
                zip.putNextEntry(ZipEntry("labels.jsonl"))
                zip.write(labels.toString().toByteArray())
                zip.closeEntry()

                zip.putNextEntry(ZipEntry("meta.json"))
                zip.write(
                    json.encodeToString(
                        Meta.serializer(),
                        Meta(
                            schema = BUNDLE_SCHEMA,
                            rasterizerVersion = RASTERIZER_VERSION,
                            appVersionName = versionName,
                            exportedAt = System.currentTimeMillis(),
                            pairCount = exported,
                        ),
                    ).toByteArray()
                )
                zip.closeEntry()
            }
            Result.success(exported)
        } catch (e: Exception) {
            Log.e(TAG, "training-bundle export failed", e)
            Result.failure(e)
        }
    }
}
