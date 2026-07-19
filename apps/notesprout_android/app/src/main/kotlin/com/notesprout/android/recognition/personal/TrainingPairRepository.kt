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
 * Every capture goes through [captureAllowed]: the personalization toggle is the single
 * gate. Encrypted notebooks are NOT excluded — with encrypt-by-default, excluding them
 * would disable the feature entirely. Nothing captured here leaves the device unless the
 * user explicitly exports a training bundle (settings → "Export training data…").
 *
 * The store is SQLCipher-encrypted under the global key (see `HwrTrainingDatabase`).
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

    fun captureAllowed(context: Context): Boolean =
        HwrSettings.personalizationEnabled(context)

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
     * How long after an object's ink was captured an edit still counts as "fixing the
     * recognition". Past this, editing a heading means the user changed their mind about
     * what it should say — that is a rewrite, not evidence the engine misread the ink.
     */
    private const val CORRECTION_WINDOW_MS = 5 * 60 * 1000L

    /**
     * A human corrected/confirmed the text of an object whose ink was captured earlier
     * (e.g. heading edit). Upgrades the stored pair; silently no-ops when no pair exists
     * (pre-feature object, encrypted notebook, capture disabled at conversion time).
     *
     * Only the FIRST edit, within [CORRECTION_WINDOW_MS] of capture, is treated as a
     * correction. Later edits are rewordings whose text no longer describes the stored ink;
     * learning from them would teach the engine a bogus "wrong → right" substitution.
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
            if (existing.confirmed) {
                Slog.d(TAG) { "edit ignored for training — pair already corrected once" }
                return@withContext
            }
            if (System.currentTimeMillis() - existing.createdAt > CORRECTION_WINDOW_MS) {
                Slog.d(TAG) { "edit ignored for training — outside the correction window" }
                return@withContext
            }
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
                HwrTrainingDatabase.dao(context).confirmedCorrections()
                    .map { it.originalText to it.label }
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
