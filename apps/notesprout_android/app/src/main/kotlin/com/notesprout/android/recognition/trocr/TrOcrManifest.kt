package com.notesprout.android.recognition.trocr

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Manifest of a TrOCR model bundle (`manifest.json` inside the bundle zip).
 *
 * Produced by `tools/hwr/make_bundle.py` — field names must stay in sync with it.
 * All runtime configuration (token ids, image normalization, vocab size) travels here,
 * read from the Hugging Face configs at export time; the app never hardcodes them.
 */
@Serializable
data class TrOcrManifest(
    val schema: Int,
    val name: String,
    val versionId: String,
    val createdAt: Long,
    val personalized: Boolean = false,
    val baseModel: String,
    val imageSize: Int,
    val imageMean: List<Float>,
    val imageStd: List<Float>,
    val vocabSize: Int,
    val decoderStartTokenId: Int,
    val bosTokenId: Int,
    val eosTokenId: Int,
    val padTokenId: Int,
    val maxLength: Int,
    /** In-bundle file name → SHA-256 hex. Verified on import before activation. */
    val files: Map<String, String>,
) {
    companion object {
        /** Highest manifest schema this app version understands. */
        const val SUPPORTED_SCHEMA = 1

        const val FILE_ENCODER = "encoder_model.onnx"

        /** Step-1 decoder: input_ids + encoder_hidden_states → logits + all present.* (incl. cross-attention KV). */
        const val FILE_DECODER_INIT = "decoder_model.onnx"

        /** Steps 2+: last token + past_key_values.* → logits + present.*.decoder.* (cross KV re-fed unchanged). */
        const val FILE_DECODER_PAST = "decoder_with_past_model.onnx"

        const val FILE_TOKENIZER = "tokenizer.json"
        const val FILE_MANIFEST = "manifest.json"

        private val codec = Json { ignoreUnknownKeys = true }

        fun fromJson(json: String): TrOcrManifest = codec.decodeFromString(serializer(), json)
    }
}
