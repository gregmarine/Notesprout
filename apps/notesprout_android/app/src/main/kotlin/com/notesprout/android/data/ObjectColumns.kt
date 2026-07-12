package com.notesprout.android.data

import android.graphics.RectF

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
