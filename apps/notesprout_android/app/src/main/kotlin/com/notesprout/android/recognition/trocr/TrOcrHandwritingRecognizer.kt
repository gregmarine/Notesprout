package com.notesprout.android.recognition.trocr

import android.content.Context
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.notesprout.android.core.Slog
import com.notesprout.android.data.LiveStroke
import com.notesprout.android.recognition.HandwritingRecognizer
import com.notesprout.android.recognition.HwrSettings
import com.notesprout.android.recognition.personal.CorrectionMemory
import com.notesprout.android.recognition.personal.TrainingPairRepository
import com.notesprout.android.recognition.personal.UserLexicon
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * TrOCR-based [HandwritingRecognizer] — the personalizable second engine.
 *
 * Image path: strokes → [LineRasterizer] → encoder/decoder via [TrOcrSession] →
 * [SentencePieceTokenizer.decode]. [preContext] is accepted for interface compatibility
 * but unused (an image model has no text-context input); Phase-2 correction memory will
 * consume it instead.
 *
 * Sessions load lazily on first recognition (~model-size heap) and never at app startup.
 * [isReady] only checks that a model bundle is installed. Decodes are serialized by a
 * [Mutex] — recognition is per-line and callers (RTR, export) are already sequential.
 */
class TrOcrHandwritingRecognizer(
    private val context: Context,
    private val ioScope: CoroutineScope,
) : HandwritingRecognizer {

    override val engineName: String = "trocr"

    val modelStore = TrOcrModelStore(context)

    private val mutex = Mutex()
    private var session: TrOcrSession? = null
    private var tokenizer: SentencePieceTokenizer? = null
    private var rasterizer: LineRasterizer? = null
    private var loadedVersionId: String? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun isReady(): Boolean = modelStore.activeModelDir() != null

    override fun recognize(
        strokes: List<LiveStroke>,
        bounds: RectF,
        onResult: (String) -> Unit,
    ) {
        ioScope.launch {
            val text = recognizeSegment(strokes, bounds, preContext = "")
            mainHandler.post { onResult(text) }
        }
    }

    override suspend fun recognizeSegment(
        strokes: List<LiveStroke>,
        bounds: RectF,
        preContext: String,
        lineHeightHint: Float,
    ): String = withContext(Dispatchers.IO) {
        if (strokes.isEmpty()) return@withContext HandwritingRecognizer.FALLBACK_TEXT
        mutex.withLock {
            try {
                val (sess, tok, raster) = ensurePipeline()
                    ?: return@withLock HandwritingRecognizer.FALLBACK_TEXT
                val personalization = ensurePersonalization(tok)
                val pixels = raster.rasterize(strokes)
                val processors = personalization?.lexicon
                    ?.takeIf { !it.isEmpty }
                    ?.let { listOf(it.processor()) }
                    ?: emptyList()
                val ids = sess.generate(pixels, processors)
                var text = tok.decode(ids)
                personalization?.memory?.takeIf { !it.isEmpty }?.let { text = it.apply(text) }
                text.ifBlank { HandwritingRecognizer.FALLBACK_TEXT }
            } catch (e: Exception) {
                Log.e(TAG, "TrOCR recognition failed", e)
                HandwritingRecognizer.FALLBACK_TEXT
            }
        }
    }

    /**
     * Load the ORT sessions + personalization ahead of an anticipated recognition, off the
     * interactive path. Called when the user makes a stroke selection (a heading / text
     * conversion may follow) — the ~1 s cold session load then overlaps their next taps
     * instead of landing after them.
     *
     * Fire-and-forget and idempotent: no-ops when already warm, and skipped entirely if a
     * recognition is in flight (that call is already doing the loading).
     */
    fun warmUp() {
        if (!isReady()) return
        if (session?.isLoaded == true && loadedVersionId != null) return
        ioScope.launch(Dispatchers.IO) {
            if (!mutex.tryLock()) return@launch
            try {
                val (sess, tok, _) = ensurePipeline() ?: return@launch
                sess.ensureLoaded()
                ensurePersonalization(tok)
            } catch (e: Exception) {
                Log.e(TAG, "TrOCR warm-up failed", e)
            } finally {
                mutex.unlock()
            }
        }
    }

    private class Personalization(
        val lexicon: UserLexicon,
        val memory: CorrectionMemory,
        val builtAtCount: Int,
    )

    private var personalization: Personalization? = null

    /**
     * Lexicon + correction memory built from confirmed training pairs; rebuilt when the
     * confirmed count changes (one COUNT query per line — cheap). Null when the
     * personalization toggle is off. Called under [mutex] on Dispatchers.IO.
     */
    private suspend fun ensurePersonalization(tokenizer: SentencePieceTokenizer): Personalization? {
        if (!HwrSettings.personalizationEnabled(context)) return null
        val count = TrainingPairRepository.confirmedCount(context)
        val cached = personalization
        if (cached != null && cached.builtAtCount == count) return cached
        val labels = TrainingPairRepository.confirmedLabels(context)
        val corrections = TrainingPairRepository.correctionPairs(context)
        val built = Personalization(
            lexicon = UserLexicon.build(labels, tokenizer),
            memory = CorrectionMemory.build(corrections),
            builtAtCount = count,
        )
        personalization = built
        Slog.d(TAG) { "personalization rebuilt: $count confirmed pairs" }
        return built
    }

    /** Load (or reload after a model switch) the session + tokenizer + rasterizer triple. */
    private fun ensurePipeline(): Triple<TrOcrSession, SentencePieceTokenizer, LineRasterizer>? {
        val dir = modelStore.activeModelDir() ?: return null
        val manifest = modelStore.activeManifest() ?: return null

        if (loadedVersionId != manifest.versionId) {
            session?.close(); session = null
            tokenizer = null; rasterizer = null

            val newSession = TrOcrSession(dir, manifest)
            val tokFile = File(dir, TrOcrManifest.FILE_TOKENIZER)
            val newTokenizer = tokFile.inputStream().buffered().use {
                SentencePieceTokenizer.fromTokenizerJson(
                    it,
                    extraSpecialIds = listOf(
                        manifest.bosTokenId, manifest.eosTokenId,
                        manifest.padTokenId, manifest.decoderStartTokenId,
                    ),
                )
            }
            session = newSession
            tokenizer = newTokenizer
            rasterizer = LineRasterizer(
                imageSize = manifest.imageSize,
                mean = manifest.imageMean.toFloatArray(),
                std = manifest.imageStd.toFloatArray(),
            )
            loadedVersionId = manifest.versionId
        }
        return Triple(session!!, tokenizer!!, rasterizer!!)
    }

    /** Session load time of the last cold load, for HwrLab reporting. */
    fun lastLoadMillis(): Long = session?.lastLoadMillis ?: -1

    /**
     * Release the ORT sessions (~model-size native heap) if no recognition is in flight —
     * wired to Application.onTrimMemory. They reload lazily on the next recognition.
     */
    fun releaseIfIdle() {
        if (mutex.tryLock()) {
            try {
                session?.close()
                session = null
                loadedVersionId = null
            } finally {
                mutex.unlock()
            }
        }
    }

    override fun close() {
        session?.close()
        session = null
        tokenizer = null
        rasterizer = null
        loadedVersionId = null
    }

    companion object {
        private const val TAG = "TrOcrRecognizer"
    }
}
