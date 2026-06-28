package com.notesprout.android.data

import android.graphics.RectF
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Serialized payload of a `type = "link"` row (the `data` column), mirroring the heading
 * "holds objects" pattern but **heterogeneously**: a link wraps any mix of strokes, headings,
 * text objects, lines, and shapes captured at link-creation time (fresh-UUID copies).
 *
 * Embedded strokes/headings/text reuse the existing [@Serializable] render models so the
 * view's draw helpers can paint them unchanged. Lines use [EmbeddedLine] and shapes use
 * [EmbeddedShape] — density-independent carriers storing dp values + bbox.
 */
@Serializable
data class LinkObject(
    val target: LinkTarget,
    val chrome: LinkChrome,
    val strokes: List<LiveStroke> = emptyList(),
    val headings: List<HeadingStroke> = emptyList(),
    val textObjects: List<TextRender> = emptyList(),
    val lines: List<EmbeddedLine> = emptyList(),
    val shapes: List<EmbeddedShape> = emptyList(),
) {
    fun toJson(): String = Json.encodeToString(serializer(), this)

    companion object {
        fun fromJson(json: String): LinkObject = Json.decodeFromString(serializer(), json)
    }
}

/**
 * Density-independent serialized carrier for a line held inside a link.
 * Stores the line's bbox plus the same dp fields as [LineObject] (NOT [LineRender]'s computed
 * px) so a link round-trips correctly across devices of differing display density.
 *
 * [boundingBox] uses [RectFSerializer] from HeadingStroke.kt.
 */
@Serializable
data class EmbeddedLine(
    val id: String,
    @Serializable(with = RectFSerializer::class)
    val boundingBox: RectF,
    val style: LineStyle,
    val orientation: LineOrientation,
    val strokeWidthDp: Float = 1f,
    val dotSpacingDp: Float = 0f,
)

/**
 * Inflate an [EmbeddedLine] into a render-time [LineRender], computing the line endpoints from
 * its bounding box + orientation (same derivation as loading a `type = "line"` row) and converting
 * the stored dp dot spacing into px for the current [density].
 */
fun EmbeddedLine.toLineRender(density: Float): LineRender {
    val box = boundingBox
    val startX: Float; val startY: Float; val endX: Float; val endY: Float
    when (orientation) {
        LineOrientation.HORIZONTAL -> {
            startX = box.left; endX = box.right; startY = box.centerY(); endY = box.centerY()
        }
        LineOrientation.VERTICAL -> {
            startX = box.centerX(); endX = box.centerX(); startY = box.top; endY = box.bottom
        }
    }
    return LineRender(id, RectF(box), startX, startY, endX, endY, style, orientation, strokeWidthDp, dotSpacingDp * density)
}

/** Capture a render-time [LineRender] as a density-independent [EmbeddedLine] for storage in a link. */
fun LineRender.toEmbeddedLine(density: Float): EmbeddedLine =
    EmbeddedLine(id, RectF(boundingBox), style, orientation, strokeWidthDp, dotSpacingPx / density)

/**
 * Density-independent serialized carrier for a shape held inside a link.
 * Stores dp-based geometry so the link round-trips correctly across differing display densities.
 */
@Serializable
data class EmbeddedShape(
    val id: String,
    @Serializable(with = RectFSerializer::class)
    val boundingBox: RectF,
    val type: ShapeType,
    val centerX: Float,
    val centerY: Float,
    val width: Float,
    val height: Float,
    val rotationDeg: Float,
    val strokeWidthDp: Float,
    val aspectLocked: Boolean,
    val pointCount: Int = 5,
)

fun EmbeddedShape.toShapeRender(density: Float): ShapeRender = ShapeRender(
    id = id,
    boundingBox = RectF(boundingBox),
    type = type,
    centerX = centerX,
    centerY = centerY,
    width = width,
    height = height,
    rotationDeg = rotationDeg,
    strokeWidthPx = strokeWidthDp * density,
    aspectLocked = aspectLocked,
    pointCount = pointCount,
)

fun ShapeRender.toEmbeddedShape(density: Float): EmbeddedShape = EmbeddedShape(
    id = id,
    boundingBox = RectF(boundingBox),
    type = type,
    centerX = centerX,
    centerY = centerY,
    width = width,
    height = height,
    rotationDeg = rotationDeg,
    strokeWidthDp = strokeWidthPx / density,
    aspectLocked = aspectLocked,
    pointCount = pointCount,
)
