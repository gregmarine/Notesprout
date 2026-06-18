package com.notesprout.android.data.index

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val codec = Json {
    encodeDefaults = true
    explicitNulls = false
    ignoreUnknownKeys = true
}

/**
 * The `data` column payload of a global-index `type="template"` row. The template NAME lives in
 * the [ObjectEntity.name] column (like notebooks/folders) — NOT in this JSON. The full-resolution
 * PNG is stored base64 (NO_WRAP) in [image].
 */
@Serializable
data class TemplateObject(
    val width: Int = 0,
    val height: Int = 0,
    val image: String = "",   // full-resolution PNG, base64 (NO_WRAP)
) {
    fun toJson(): String = codec.encodeToString(serializer(), this)

    companion object {
        fun fromJson(json: String): TemplateObject? =
            try { codec.decodeFromString(serializer(), json) } catch (e: Exception) { null }
    }
}
