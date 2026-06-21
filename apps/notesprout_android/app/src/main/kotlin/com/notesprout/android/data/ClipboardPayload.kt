package com.notesprout.android.data

import android.graphics.RectF
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class BoundingBoxData(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    fun toRectF(): RectF = RectF(left, top, right, bottom)

    companion object {
        fun from(r: RectF): BoundingBoxData = BoundingBoxData(r.left, r.top, r.right, r.bottom)
    }
}

@Serializable
data class ClipItem(val type: String, val boundingBox: BoundingBoxData, val data: String)

@Serializable
data class ClipboardPayload(
    val items: List<ClipItem>,
    val boundingBox: BoundingBoxData,
    val sourceNotebookId: String,
    val sourceEncrypted: Boolean,
    val copiedAt: Long,
) {
    fun toJson(): String = codec.encodeToString(serializer(), this)

    companion object {
        private val codec = Json { ignoreUnknownKeys = true }

        fun fromJson(json: String): ClipboardPayload = codec.decodeFromString(serializer(), json)
    }
}
