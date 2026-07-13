package com.notesprout.android.recognition.personal

import android.content.Context
import com.notesprout.android.core.Slog
import com.notesprout.android.data.LiveStroke
import com.notesprout.android.data.hwr.HwrTrainingDatabase
import com.notesprout.android.data.hwr.TrainingPairEntity
import com.notesprout.android.recognition.HwrSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * CRUD over the handwriting training-pair store (`filesDir/hwr/training.db`).
 *
 * Every capture goes through [captureAllowed]: personalization must be enabled AND the
 * source notebook must be plaintext (leak hygiene — encrypted-notebook ink/text never
 * lands in this unencrypted store). Enrollment passes `encryptedSource = false` by
 * definition (its prescribed sentences are the user's explicit opt-in).
 *
 * All methods suspend and run on Dispatchers.IO. Captures are fire-and-forget from the
 * UI's perspective — a failed capture must never break the calling flow.
 */
object TrainingPairRepository {

    private const val TAG = "TrainingPairRepo"

    /** Keep the newest N pairs; unconfirmed rows are evicted first. */
    private const val CAP = 2000

    const val SOURCE_ENROLLMENT = "enrollment"
    const val SOURCE_LINE_CORRECTION = "line_correction"
    const val SOURCE_HEADING_CONVERSION = "heading_conversion"
    const val SOURCE_HEADING_CORRECTION = "heading_correction"
    const val SOURCE_LAB = "lab"

    private val json = Json { ignoreUnknownKeys = true }

    fun captureAllowed(context: Context, encryptedSource: Boolean): Boolean =
        HwrSettings.personalizationEnabled(context) && !encryptedSource

    /**
     * Store a new pair. [confirmed] is false for engine output (e.g. a fresh heading
     * conversion) and true for human-provided text. [objectId] keys later upgrades.
     */
    suspend fun addPair(
        context: Context,
        source: String,
        strokes: List<LiveStroke>,
        label: String,
        confirmed: Boolean,
        originalText: String? = null,
        objectId: String? = null,
        notebookId: String? = null,
        pageId: String? = null,
        refLineHeight: Float = 0f,
    ) = withContext(Dispatchers.IO) {
        if (strokes.isEmpty() || label.isBlank()) return@withContext
        try {
            val dao = HwrTrainingDatabase.dao(context)
            val now = System.currentTimeMillis()
            val existing = objectId?.let { dao.getByObjectId(it) }
            dao.insert(
                TrainingPairEntity(
                    id = existing?.id ?: UUID.randomUUID().toString(),
                    source = source,
                    label = label.trim(),
                    originalText = originalText ?: existing?.originalText,
                    strokesJson = json.encodeToString(ListSerializer(LiveStroke.serializer()), strokes),
                    refLineHeight = refLineHeight,
                    notebookId = notebookId,
                    pageId = pageId,
                    objectId = objectId,
                    confirmed = confirmed,
                    createdAt = existing?.createdAt ?: now,
                    updatedAt = now,
                )
            )
            dao.pruneToCap(CAP)
            Slog.d(TAG) { "pair stored source=$source confirmed=$confirmed (${strokes.size} strokes, ${label.length} chars)" }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "capture failed", e)
        }
    }

    /**
     * A human corrected/confirmed the text of an object whose ink was captured earlier
     * (e.g. heading edit). Upgrades the stored pair; silently no-ops when no pair exists
     * (pre-feature object, encrypted notebook, capture disabled at conversion time).
     */
    suspend fun confirmByObjectId(
        context: Context,
        objectId: String,
        newLabel: String,
        source: String,
    ) = withContext(Dispatchers.IO) {
        if (newLabel.isBlank()) return@withContext
        try {
            val dao = HwrTrainingDatabase.dao(context)
            val existing = dao.getByObjectId(objectId) ?: return@withContext
            dao.insert(
                existing.copy(
                    source = source,
                    label = newLabel.trim(),
                    confirmed = true,
                    updatedAt = System.currentTimeMillis(),
                )
            )
            Slog.d(TAG) { "pair confirmed via $source (${newLabel.length} chars)" }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "confirm failed", e)
        }
    }

    suspend fun confirmedPairs(context: Context): List<TrainingPairEntity> =
        withContext(Dispatchers.IO) {
            try { HwrTrainingDatabase.dao(context).confirmedPairs() } catch (e: Exception) {
                android.util.Log.e(TAG, "read failed", e); emptyList()
            }
        }

    suspend fun confirmedLabels(context: Context): List<String> =
        withContext(Dispatchers.IO) {
            try { HwrTrainingDatabase.dao(context).confirmedLabels() } catch (e: Exception) {
                android.util.Log.e(TAG, "read failed", e); emptyList()
            }
        }

    suspend fun confirmedCount(context: Context): Int = withContext(Dispatchers.IO) {
        try { HwrTrainingDatabase.dao(context).confirmedCount() } catch (e: Exception) { 0 }
    }

    suspend fun clearAll(context: Context) = withContext(Dispatchers.IO) {
        try { HwrTrainingDatabase.dao(context).deleteAll() } catch (e: Exception) {
            android.util.Log.e(TAG, "clear failed", e)
        }
    }

    /**
     * (originalText, label) pairs of human-confirmed corrections where the engine's
     * original reading is known — the input to [CorrectionMemory].
     */
    suspend fun correctionPairs(context: Context): List<Pair<String, String>> =
        withContext(Dispatchers.IO) {
            try {
                HwrTrainingDatabase.dao(context).confirmedPairs()
                    .mapNotNull { p ->
                        val orig = p.originalText?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                        if (orig == p.label) null else orig to p.label
                    }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "read failed", e); emptyList()
            }
        }

    /** Decode a pair's strokes for re-rasterization (training export, previews). */
    fun decodeStrokes(pair: TrainingPairEntity): List<LiveStroke> = try {
        json.decodeFromString(ListSerializer(LiveStroke.serializer()), pair.strokesJson)
    } catch (e: Exception) {
        android.util.Log.e(TAG, "stroke decode failed", e)
        emptyList()
    }
}
