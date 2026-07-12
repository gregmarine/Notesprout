package com.notesprout.android.data

import android.graphics.RectF
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.Inflater
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Object ⇄ columnar-row mapping (data-model-optimization Phase 1, step 4).
 *
 * The `.soil` `notebook` table moved off the opaque `data` JSON to typed columns (see [SoilSchema]).
 * These `toRow`/`to<Type>` helpers are the single boundary between the render/domain models and the
 * columnar [NotebookObject]. Every reader is **format-agnostic**: it uses the typed columns when
 * present and falls back to the legacy `data` JSON otherwise, so pre-migration rows keep working and
 * convert lazily on their next save. Strokes have their own path in [LiveStroke]; composites
 * (heading/text/link/sticky) keep their nested sub-object collections in the binary `blob`.
 *
 * Only the `.soil` path (NotebookActivity / NotebookExporter) uses these — the calendar/scratchpad
 * index tables ([com.notesprout.android.data.index.ObjectEntity]) stay JSON (that is Phase 2).
 */

/**
 * In-place columnar update from a `toRow()`-built [row]: writes its payload columns + `updatedAt`
 * (structural fields — parentId/order/createdAt — are ignored, so `toRow`'s placeholders for them are
 * irrelevant). The one primitive behind every non-stroke lasso-move / edit re-persist.
 */
suspend fun NotebookDao.updateColumns(row: NotebookObject) = updateObjectColumns(
    row.id, row.x, row.y, row.width, row.height, row.text, row.color, row.strokeWidth, row.refId,
    row.level, row.lineStyle, row.orientation, row.dotSpacing, row.shapeType, row.centerX, row.centerY,
    row.rotationDeg, row.pointCount, row.contentW, row.contentH, row.linkTarget, row.chrome, row.flags,
    row.blob, row.updatedAt,
)

// ── Composite nested content (zlib(JSON) in the binary blob) ─────────────────
// Composites (heading/text fallback strokes, link/sticky sub-collections) keep their nested content
// atomic in `blob` as zlib(JSON) — a format change off the TEXT `data` column, not normalization.

private val compositeJson = Json { encodeDefaults = false; ignoreUnknownKeys = true }
private val strokeListSerializer = ListSerializer(LiveStroke.serializer())

internal fun deflateString(s: String): ByteArray {
    val d = Deflater(Deflater.BEST_COMPRESSION); d.setInput(s.toByteArray()); d.finish()
    val out = ByteArrayOutputStream(maxOf(16, s.length / 2)); val buf = ByteArray(4096)
    while (!d.finished()) out.write(buf, 0, d.deflate(buf)); d.end()
    return out.toByteArray()
}

internal fun inflateString(b: ByteArray): String {
    val inf = Inflater(); inf.setInput(b)
    val out = ByteArrayOutputStream(maxOf(16, b.size * 3)); val buf = ByteArray(4096)
    while (!inf.finished()) { val n = inf.inflate(buf); if (n == 0 && inf.needsInput()) break; out.write(buf, 0, n) }
    inf.end(); return out.toByteArray().toString(Charsets.UTF_8)
}

private fun packStrokes(list: List<LiveStroke>): ByteArray =
    deflateString(compositeJson.encodeToString(strokeListSerializer, list))
private fun unpackStrokes(b: ByteArray): List<LiveStroke> =
    compositeJson.decodeFromString(strokeListSerializer, inflateString(b))

/** Geometry from the typed x/y/width/height columns, or the legacy `boundingBox` JSON. */
fun NotebookObject.boxOrLegacy(): RectF? {
    val lx = x; val ly = y; val lw = width; val lh = height
    return if (lx != null && ly != null && lw != null && lh != null) RectF(lx, ly, lx + lw, ly + lh)
    else parseBoundingBox(boundingBox)
}

// ── Line ───────────────────────────────────────────────────────────────────

/** Build a columnar `line` row from a render-time [LineRender] (dp fields; px→dp for dot spacing). */
fun LineRender.toRow(
    parentId: String, order: Int, createdAt: Long, updatedAt: Long, density: Float, deletedAt: Long? = null,
): NotebookObject {
    val b = boundingBox
    return NotebookObject(
        id = id, parentId = parentId, boundingBox = "", sortOrder = order,
        createdAt = createdAt, updatedAt = updatedAt, deletedAt = deletedAt,
        type = TYPE_LINE, data = "",
        x = b.left, y = b.top, width = b.width(), height = b.height(),
        lineStyle = style.name, orientation = orientation.name,
        strokeWidth = strokeWidthDp, dotSpacing = dotSpacingPx / density,
    )
}

/** Decode a `line` row to a render-time [LineRender] (columns when present, else legacy JSON). */
fun NotebookObject.toLineRender(density: Float): LineRender? {
    val box = boxOrLegacy() ?: return null
    val style: LineStyle; val orient: LineOrientation; val swDp: Float; val dotDp: Float
    val ls = lineStyle
    if (ls != null) {
        style = runCatching { LineStyle.valueOf(ls) }.getOrNull() ?: return null
        orient = runCatching { LineOrientation.valueOf(orientation ?: "HORIZONTAL") }.getOrNull() ?: return null
        swDp = strokeWidth ?: 1f
        dotDp = dotSpacing ?: 0f
    } else {
        val lo = runCatching { LineObject.fromJson(data) }.getOrNull() ?: return null
        style = lo.style; orient = lo.orientation; swDp = lo.strokeWidthDp; dotDp = lo.dotSpacingDp
    }
    val startX: Float; val startY: Float; val endX: Float; val endY: Float
    when (orient) {
        LineOrientation.HORIZONTAL -> { startX = box.left; endX = box.right; startY = box.centerY(); endY = box.centerY() }
        LineOrientation.VERTICAL   -> { startX = box.centerX(); endX = box.centerX(); startY = box.top; endY = box.bottom }
    }
    return LineRender(id, box, startX, startY, endX, endY, style, orient, swDp, dotDp * density)
}

// ── Shape ──────────────────────────────────────────────────────────────────
// The stored bounding box is redundant (ShapeRender.from recomputes the AABB from the oriented box +
// rotation), so shapes persist only their params; x/y stay null and width/height are the ORIENTED box.

/** Build a columnar `shape` row from a render-time [ShapeRender]. */
fun ShapeRender.toRow(
    parentId: String, order: Int, createdAt: Long, updatedAt: Long, density: Float, deletedAt: Long? = null,
): NotebookObject = NotebookObject(
    id = id, parentId = parentId, boundingBox = "", sortOrder = order,
    createdAt = createdAt, updatedAt = updatedAt, deletedAt = deletedAt,
    type = TYPE_SHAPE, data = "",
    width = width, height = height, strokeWidth = strokeWidthPx / density,
    shapeType = type.name, centerX = centerX, centerY = centerY, rotationDeg = rotationDeg,
    pointCount = pointCount, flags = if (aspectLocked) 1 else 0,
)

/** Decode a `shape` row to a render-time [ShapeRender] (columns when present, else legacy JSON). */
fun NotebookObject.toShapeRender(density: Float): ShapeRender? {
    val st = shapeType
    val obj = if (st != null) {
        ShapeObject(
            type = runCatching { ShapeType.valueOf(st) }.getOrNull() ?: return null,
            centerX = centerX ?: 0f, centerY = centerY ?: 0f, width = width ?: 0f, height = height ?: 0f,
            rotationDeg = rotationDeg ?: 0f, strokeWidthDp = strokeWidth ?: 1f,
            aspectLocked = ((flags ?: 0) and 1) != 0, pointCount = pointCount ?: 5,
        )
    } else runCatching { ShapeObject.fromJson(data) }.getOrNull() ?: return null
    return ShapeRender.from(id, obj, density)
}

// ── Text ───────────────────────────────────────────────────────────────────
// Recognized text → `text` column. The rare unrecognized fallback (blank text + embedded strokes)
// keeps its strokes in `blob`. A columnar row has data == "".

fun TextRender.toRow(parentId: String, order: Int, createdAt: Long, updatedAt: Long, deletedAt: Long? = null): NotebookObject {
    val b = boundingBox
    val fallback = strokes
    return NotebookObject(
        id = id, parentId = parentId, boundingBox = "", sortOrder = order,
        createdAt = createdAt, updatedAt = updatedAt, deletedAt = deletedAt, type = TYPE_TEXT, data = "",
        x = b.left, y = b.top, width = b.width(), height = b.height(),
        text = text,
        blob = if (!fallback.isNullOrEmpty()) packStrokes(fallback) else null,
    )
}

fun NotebookObject.toTextRender(): TextRender? {
    val box = boxOrLegacy() ?: return null
    return if (data.isEmpty()) {
        TextRender(id = id, boundingBox = box, text = text ?: "", strokes = blob?.let { unpackStrokes(it) })
    } else {
        val t = runCatching { TextObject.fromJson(data) }.getOrNull() ?: return null
        TextRender(id = id, boundingBox = box, text = t.text, strokes = t.strokes)
    }
}

// ── Heading ────────────────────────────────────────────────────────────────
// Recognized heading → `text` (recognizedText) + `level`. The ML-fail stroke-only fallback
// (recognizedText == null) keeps its strokes in `blob`. A columnar row has data == "".

fun HeadingStroke.toRow(parentId: String, order: Int, createdAt: Long, updatedAt: Long, deletedAt: Long? = null): NotebookObject {
    val b = boundingBox
    return NotebookObject(
        id = id, parentId = parentId, boundingBox = "", sortOrder = order,
        createdAt = createdAt, updatedAt = updatedAt, deletedAt = deletedAt, type = TYPE_HEADING, data = "",
        x = b.left, y = b.top, width = b.width(), height = b.height(),
        text = recognizedText, level = level,
        blob = if (recognizedText == null && strokes.isNotEmpty()) packStrokes(strokes) else null,
    )
}

fun NotebookObject.toHeadingStroke(): HeadingStroke? {
    val box = boxOrLegacy() ?: return null
    return if (data.isEmpty()) {
        HeadingStroke(
            id = id, boundingBox = box,
            strokes = blob?.let { unpackStrokes(it) } ?: emptyList(),
            recognizedText = text, level = level ?: 1,
        )
    } else {
        val h = runCatching { HeadingObject.fromJson(data) }.getOrNull() ?: return null
        HeadingStroke(id, box, h.strokes, h.recognizedText, h.level)
    }
}

// ── Link ─────────────────────────────────────────────────────────────────────
// A link's scalar target/chrome go to the linkTarget/chrome columns; its heterogeneous nested
// content (strokes/headings/text/lines/shapes) stays atomic in `blob` as zlib(JSON(LinkObject)) —
// the nested payload is exactly what the legacy `data` column held, just compressed. A columnar
// row has data == "". Density-independent embedded lines/shapes round-trip via LinkObject.

fun LinkRender.toRow(
    parentId: String, order: Int, createdAt: Long, updatedAt: Long, density: Float, deletedAt: Long? = null,
): NotebookObject {
    val b = boundingBox
    return NotebookObject(
        id = id, parentId = parentId, boundingBox = "", sortOrder = order,
        createdAt = createdAt, updatedAt = updatedAt, deletedAt = deletedAt, type = TYPE_LINK, data = "",
        x = b.left, y = b.top, width = b.width(), height = b.height(),
        linkTarget = compositeJson.encodeToString(LinkTarget.serializer(), target),
        chrome = chrome.name,
        blob = deflateString(toLinkObject(density).toJson()),
    )
}

/**
 * Decode a `link` row to a render-time [LinkRender] (columns+blob when present, else legacy JSON).
 * Mirrors the historical readers: embedded strokes/headings/text/lines are inflated; embedded
 * shapes are intentionally NOT surfaced (unchanged from the prior load path).
 */
fun NotebookObject.toLinkRender(density: Float): LinkRender? {
    val box = boxOrLegacy() ?: return null
    val lo = if (data.isEmpty()) {
        blob?.let { runCatching { LinkObject.fromJson(inflateString(it)) }.getOrNull() } ?: return null
    } else {
        runCatching { LinkObject.fromJson(data) }.getOrNull() ?: return null
    }
    return LinkRender(
        id = id, boundingBox = box, target = lo.target, chrome = lo.chrome,
        strokes = lo.strokes, headings = lo.headings, textObjects = lo.textObjects,
        lines = lo.lines.map { it.toLineRender(density) },
    )
}

// ── Sticky note ──────────────────────────────────────────────────────────────
// The icon rectangle goes to x/y/width/height; the content-window pixel size goes to
// contentW/contentH; the embedded content (own coordinate space) stays atomic in `blob` as
// zlib(JSON(StickyNoteObject)). A columnar row has data == "".

fun StickyNoteRender.toRow(
    parentId: String, order: Int, createdAt: Long, updatedAt: Long, density: Float, deletedAt: Long? = null,
): NotebookObject {
    val b = boundingBox
    return NotebookObject(
        id = id, parentId = parentId, boundingBox = "", sortOrder = order,
        createdAt = createdAt, updatedAt = updatedAt, deletedAt = deletedAt, type = TYPE_STICKY_NOTE, data = "",
        x = b.left, y = b.top, width = b.width(), height = b.height(),
        contentW = contentWidth, contentH = contentHeight,
        blob = deflateString(toStickyNoteObject(density).toJson()),
    )
}

/**
 * Decode a `sticky_note` row to a render-time [StickyNoteRender] (columns+blob when present, else
 * legacy JSON). Embedded shapes ARE surfaced (matches the exporter's lossless read).
 */
fun NotebookObject.toStickyNoteRender(density: Float): StickyNoteRender? {
    val box = boxOrLegacy() ?: return null
    val obj = if (data.isEmpty()) {
        blob?.let { runCatching { StickyNoteObject.fromJson(inflateString(it)) }.getOrNull() } ?: return null
    } else {
        runCatching { StickyNoteObject.fromJson(data) }.getOrNull() ?: return null
    }
    return StickyNoteRender(
        id = id, boundingBox = box,
        strokes = obj.strokes, headings = obj.headings, textObjects = obj.textObjects,
        lines = obj.lines.map { it.toLineRender(density) }, shapes = obj.shapes,
        contentWidth = obj.contentWidth, contentHeight = obj.contentHeight,
    )
}

// ── Structural rows: page / layer / notebook-meta / template (Phase 2b) ───────
// Same lazy-coexistence contract as the content types: a columnar row has data == "" and reads from
// typed columns; legacy rows keep their JSON in `data` and read via the fallback. The `boundingBox`
// column is left untouched for pages/templates so raw-SQL dimension readers keep working.

/** Layer flag bits: isLocked = bit0, isVisible = bit1. A default content layer is VISIBLE, unlocked. */
const val LAYER_FLAG_LOCKED = 1
const val LAYER_FLAG_VISIBLE = 2
const val LAYER_FLAGS_DEFAULT = LAYER_FLAG_VISIBLE

/**
 * Page config. A columnar page keeps its size in the `boundingBox` column (`{0,0,w,h}`, untouched so
 * raw-SQL dimension readers still work) and moves only the template id into `refId`; falls back to the
 * legacy PageData JSON.
 */
fun NotebookObject.pageData(): PageData {
    if (data.isNotEmpty()) return PageData.fromJson(data)
    val bb = BoundingBox.fromJson(boundingBox)
    return PageData(width = bb?.width ?: 0f, height = bb?.height ?: 0f, template = refId ?: "")
}

/** Notebook-meta row: title→text, lastOpenedPage→refId, rtrEnabled→flags bit0; legacy JSON fallback. */
fun NotebookObject.notebookMetadata(): NotebookMetadata =
    if (data.isEmpty()) NotebookMetadata(
        id = id, title = text ?: "", lastOpenedPage = refId, rtrEnabled = ((flags ?: 0) and 1) != 0,
    )
    else NotebookMetadata.fromJson(id, data)

/**
 * Template row: name→text, size→width/height, decoded image bytes→[NotebookObject.blob]. The base64
 * image is re-encoded on read so every caller keeps receiving [TemplateData.image] as base64. Legacy
 * JSON fallback; null only when a legacy row's JSON is malformed.
 */
fun NotebookObject.templateDataOrNull(): TemplateData? {
    if (data.isNotEmpty()) return TemplateData.fromJson(data)
    val bb = BoundingBox.fromJson(boundingBox)
    return TemplateData(
        width = (bb?.width ?: 0f).toInt(), height = (bb?.height ?: 0f).toInt(), name = text ?: "",
        image = blob?.let { Base64.encodeToString(it, Base64.NO_WRAP) } ?: "",
    )
}

/** Encode a base64 template image to the binary blob (inverse of the [templateDataOrNull] read). "" → null. */
fun templateImageBlob(imageBase64: String): ByteArray? =
    if (imageBase64.isEmpty()) null
    else runCatching { Base64.decode(imageBase64, Base64.DEFAULT) }.getOrNull()

/** Write this metadata onto its existing notebook row [obj] as columnar fields (clears legacy data). */
fun NotebookMetadata.writeOnto(obj: NotebookObject, updatedAt: Long): NotebookObject = obj.copy(
    data = "", text = title, refId = lastOpenedPage, flags = if (rtrEnabled) 1 else 0, updatedAt = updatedAt,
)
