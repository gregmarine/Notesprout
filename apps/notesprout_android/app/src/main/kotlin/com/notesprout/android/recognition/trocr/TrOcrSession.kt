package com.notesprout.android.recognition.trocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.notesprout.android.core.Slog
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * Owns the three ONNX Runtime sessions of a TrOCR bundle (encoder, step-1 decoder,
 * with-past decoder) and runs the encode → autoregressive-generate pipeline.
 *
 * KV-cache protocol (see tools/hwr/make_bundle.py):
 *  - step 1 → `decoder_model.onnx`: `input_ids` + `encoder_hidden_states` →
 *    `logits` + `present.<L>.decoder.{key,value}` + `present.<L>.encoder.{key,value}`
 *  - steps 2+ → `decoder_with_past_model.onnx`: last token + `past_key_values.*` →
 *    `logits` + `present.<L>.decoder.*` only; the encoder (cross-attention) KV computed
 *    in step 1 is re-fed unchanged every step.
 *
 * Not thread-safe: callers serialize decodes (the recognizer holds a Mutex).
 * CPU execution provider only; sessions load lazily via [ensureLoaded], never at startup.
 */
class TrOcrSession(
    private val modelDir: File,
    val manifest: TrOcrManifest,
) : AutoCloseable {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private var encoder: OrtSession? = null
    private var decoderInit: OrtSession? = null
    private var decoderPast: OrtSession? = null

    /** Wall-clock ms of the last [ensureLoaded] that actually loaded, for HwrLab. */
    var lastLoadMillis: Long = -1
        private set

    val isLoaded: Boolean get() = encoder != null

    @Synchronized
    fun ensureLoaded() {
        if (encoder != null) return
        val t0 = System.currentTimeMillis()
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(
                minOf(4, Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1)
            )
        }
        encoder = env.createSession(File(modelDir, TrOcrManifest.FILE_ENCODER).absolutePath, opts)
        decoderInit = env.createSession(File(modelDir, TrOcrManifest.FILE_DECODER_INIT).absolutePath, opts)
        decoderPast = env.createSession(File(modelDir, TrOcrManifest.FILE_DECODER_PAST).absolutePath, opts)
        lastLoadMillis = System.currentTimeMillis() - t0
        Slog.d(TAG) { "Sessions loaded in ${lastLoadMillis}ms from ${manifest.versionId}" }
    }

    /**
     * Full pipeline for one line image: encoder pass + greedy decode.
     * [pixels] is the CHW tensor from [LineRasterizer]. Returns generated token ids.
     */
    suspend fun generate(
        pixels: FloatBuffer,
        processors: List<TrOcrDecoder.LogitProcessor> = emptyList(),
    ): IntArray {
        ensureLoaded()
        val enc = encoder!!; val dInit = decoderInit!!; val dPast = decoderPast!!
        val size = manifest.imageSize.toLong()

        OnnxTensor.createTensor(env, pixels, longArrayOf(1, 3, size, size)).use { pixelTensor ->
            // ---- encoder pass ----
            val encoderHidden: OnnxTensor
            enc.run(mapOf("pixel_values" to pixelTensor)).use { result ->
                encoderHidden = copyTensor(result.get(0) as OnnxTensor)
            }

            // ---- autoregressive decode ----
            // past feed: name -> owned tensor; encoder (cross) entries are set once in step 1
            val past = HashMap<String, OnnxTensor>()
            try {
                return TrOcrDecoder.greedy(
                    step = { prefixIds, state ->
                        @Suppress("UNCHECKED_CAST")
                        val stateMap = state as? HashMap<String, OnnxTensor>
                        if (stateMap == null) {
                            stepInit(dInit, prefixIds, encoderHidden, past)
                        } else {
                            stepPast(dPast, prefixIds.last(), stateMap)
                        }
                    },
                    startId = manifest.decoderStartTokenId,
                    eosId = manifest.eosTokenId,
                    maxNewTokens = manifest.maxLength,
                )
            } finally {
                past.values.forEach { it.close() }
                encoderHidden.close()
            }
        }
    }

    /** Step 1: full prefix + encoder hidden states; harvests all present.* into [past]. */
    private fun stepInit(
        session: OrtSession,
        prefixIds: IntArray,
        encoderHidden: OnnxTensor,
        past: HashMap<String, OnnxTensor>,
    ): TrOcrDecoder.StepResult {
        val ids = LongBuffer.wrap(LongArray(prefixIds.size) { prefixIds[it].toLong() })
        OnnxTensor.createTensor(env, ids, longArrayOf(1, prefixIds.size.toLong())).use { inputIds ->
            session.run(
                mapOf("input_ids" to inputIds, "encoder_hidden_states" to encoderHidden)
            ).use { result ->
                var logits: FloatArray? = null
                for (entry in result) {
                    val name = entry.key
                    val value = entry.value as OnnxTensor
                    if (name == "logits") {
                        logits = lastPositionLogits(value)
                    } else if (name.startsWith("present")) {
                        past[name.replace("present", "past_key_values")] = copyTensor(value)
                    }
                }
                return TrOcrDecoder.StepResult(requireNotNull(logits) { "decoder returned no logits" }, past)
            }
        }
    }

    /** Steps 2+: last token + past; replaces the decoder-side KV entries in place. */
    private fun stepPast(
        session: OrtSession,
        lastId: Int,
        past: HashMap<String, OnnxTensor>,
    ): TrOcrDecoder.StepResult {
        OnnxTensor.createTensor(
            env, LongBuffer.wrap(longArrayOf(lastId.toLong())), longArrayOf(1, 1)
        ).use { inputIds ->
            val feed = HashMap<String, OnnxTensor>(past.size + 1)
            feed.putAll(past)
            feed["input_ids"] = inputIds
            session.run(feed).use { result ->
                var logits: FloatArray? = null
                val updates = ArrayList<Pair<String, OnnxTensor>>()
                for (entry in result) {
                    val name = entry.key
                    val value = entry.value as OnnxTensor
                    if (name == "logits") {
                        logits = lastPositionLogits(value)
                    } else if (name.startsWith("present")) {
                        updates.add(name.replace("present", "past_key_values") to copyTensor(value))
                    }
                }
                for ((name, tensor) in updates) {
                    past.remove(name)?.close()
                    past[name] = tensor
                }
                return TrOcrDecoder.StepResult(requireNotNull(logits) { "decoder returned no logits" }, past)
            }
        }
    }

    /** logits tensor [1, seqLen, vocab] → float[vocab] for the last position. */
    private fun lastPositionLogits(tensor: OnnxTensor): FloatArray {
        val shape = tensor.info.shape
        val vocab = shape.last().toInt()
        val seqLen = shape[1].toInt()
        val buf = tensor.floatBuffer
        val out = FloatArray(vocab)
        buf.position((seqLen - 1) * vocab)
        buf.get(out)
        return out
    }

    /** Deep-copy an output tensor so it survives its OrtSession.Result being closed. */
    private fun copyTensor(tensor: OnnxTensor): OnnxTensor {
        val shape = tensor.info.shape
        val src = tensor.floatBuffer
        val copy = FloatBuffer.allocate(src.remaining())
        copy.put(src)
        copy.rewind()
        return OnnxTensor.createTensor(env, copy, shape)
    }

    @Synchronized
    override fun close() {
        decoderPast?.close(); decoderPast = null
        decoderInit?.close(); decoderInit = null
        encoder?.close(); encoder = null
    }

    companion object {
        private const val TAG = "TrOcrSession"
    }
}
