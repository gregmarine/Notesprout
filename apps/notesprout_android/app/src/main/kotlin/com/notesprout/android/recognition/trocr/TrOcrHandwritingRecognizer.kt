package com.notesprout.android.recognition.trocr

import android.content.Context
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.notesprout.android.data.LiveStroke
import com.notesprout.android.recognition.HandwritingRecognizer
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
                val pixels = raster.rasterize(strokes)
                val ids = sess.generate(pixels)
                val text = tok.decode(ids)
                text.ifBlank { HandwritingRecognizer.FALLBACK_TEXT }
            } catch (e: Exception) {
                Log.e(TAG, "TrOCR recognition failed", e)
                HandwritingRecognizer.FALLBACK_TEXT
            }
        }
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
