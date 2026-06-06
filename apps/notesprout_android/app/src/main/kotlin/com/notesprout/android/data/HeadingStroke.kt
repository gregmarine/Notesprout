package com.notesprout.android.data

import android.graphics.RectF
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Render-time representation of a heading row — NOT stored in the DB.
 * Built at load time from `type = "heading"` rows in the notebook table.
 *
 * The grey background is drawn using [boundingBox]; the embedded [strokes]
 * are replayed on top of it using the normal stroke rendering path.
 *
 * [@Serializable] so heading data can be carried in undo/redo actions
 * (e.g. [StrokesMoved], [LassoCut], [LassoDeleted], [LassoErased]).
 * [boundingBox] uses a custom [RectFSerializer] since [RectF] has no built-in
 * kotlinx.serialization support.
 */
@Serializable
data class HeadingStroke(
    val id: String,
    @Serializable(with = RectFSerializer::class)
    val boundingBox: RectF,
    val strokes: List<LiveStroke>,
    val recognizedText: String? = null,
)

@Serializable
private data class RectFSurrogate(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

internal object RectFSerializer : KSerializer<RectF> {
    private val delegate = RectFSurrogate.serializer()
    override val descriptor: SerialDescriptor = delegate.descriptor
    override fun serialize(encoder: Encoder, value: RectF) =
        delegate.serialize(encoder, RectFSurrogate(value.left, value.top, value.right, value.bottom))
    override fun deserialize(decoder: Decoder): RectF {
        val s = delegate.deserialize(decoder)
        return RectF(s.left, s.top, s.right, s.bottom)
    }
}
