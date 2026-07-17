package com.notesprout.android.data.index

import com.notesprout.android.data.NotebookObject

/**
 * Zero-logic boundary converters between the index-DB object rows ([CalendarEntity] /
 * [ScratchpadEntity]) and the `.soil` [NotebookObject].
 *
 * The three entities are field-identical (same universal-object schema + the v6 columnar columns),
 * so a calendar/scratchpad row round-trips through a plain [NotebookObject] copy. This lets the
 * calendar/scratchpad repositories reuse **every** Phase-1 columnar mapping in
 * [com.notesprout.android.data.ObjectColumns] and [com.notesprout.android.data.LiveStroke]
 * (format-agnostic reads + `toRow`/`toStrokeRow` writes) instead of duplicating the per-type
 * serialization. The converted [NotebookObject] is never inserted into the `notebook` table — it is
 * used purely as an in-memory carrier.
 */

fun CalendarEntity.toNotebookObject(): NotebookObject = NotebookObject(
    id = id, parentId = parentId, boundingBox = boundingBox, sortOrder = sortOrder,
    createdAt = createdAt, updatedAt = updatedAt, deletedAt = deletedAt, type = type, data = data,
    x = x, y = y, width = width, height = height, text = text, color = color,
    strokeWidth = strokeWidth, refId = refId, level = level, lineStyle = lineStyle,
    orientation = orientation, dotSpacing = dotSpacing, shapeType = shapeType, centerX = centerX,
    centerY = centerY, rotationDeg = rotationDeg, pointCount = pointCount, contentW = contentW,
    contentH = contentH, linkTarget = linkTarget, chrome = chrome, flags = flags, blob = blob,
)

fun NotebookObject.toCalendarEntity(): CalendarEntity = CalendarEntity(
    id = id, parentId = parentId, boundingBox = boundingBox, sortOrder = sortOrder,
    createdAt = createdAt, updatedAt = updatedAt, deletedAt = deletedAt, type = type, data = data,
    x = x, y = y, width = width, height = height, text = text, color = color,
    strokeWidth = strokeWidth, refId = refId, level = level, lineStyle = lineStyle,
    orientation = orientation, dotSpacing = dotSpacing, shapeType = shapeType, centerX = centerX,
    centerY = centerY, rotationDeg = rotationDeg, pointCount = pointCount, contentW = contentW,
    contentH = contentH, linkTarget = linkTarget, chrome = chrome, flags = flags, blob = blob,
)

fun ScratchpadEntity.toNotebookObject(): NotebookObject = NotebookObject(
    id = id, parentId = parentId, boundingBox = boundingBox, sortOrder = sortOrder,
    createdAt = createdAt, updatedAt = updatedAt, deletedAt = deletedAt, type = type, data = data,
    x = x, y = y, width = width, height = height, text = text, color = color,
    strokeWidth = strokeWidth, refId = refId, level = level, lineStyle = lineStyle,
    orientation = orientation, dotSpacing = dotSpacing, shapeType = shapeType, centerX = centerX,
    centerY = centerY, rotationDeg = rotationDeg, pointCount = pointCount, contentW = contentW,
    contentH = contentH, linkTarget = linkTarget, chrome = chrome, flags = flags, blob = blob,
)

fun NotebookObject.toScratchpadEntity(): ScratchpadEntity = ScratchpadEntity(
    id = id, parentId = parentId, boundingBox = boundingBox, sortOrder = sortOrder,
    createdAt = createdAt, updatedAt = updatedAt, deletedAt = deletedAt, type = type, data = data,
    x = x, y = y, width = width, height = height, text = text, color = color,
    strokeWidth = strokeWidth, refId = refId, level = level, lineStyle = lineStyle,
    orientation = orientation, dotSpacing = dotSpacing, shapeType = shapeType, centerX = centerX,
    centerY = centerY, rotationDeg = rotationDeg, pointCount = pointCount, contentW = contentW,
    contentH = contentH, linkTarget = linkTarget, chrome = chrome, flags = flags, blob = blob,
)

/**
 * In-place columnar update from a `toRow()`-built [row] — the calendar/scratchpad analogue of
 * [com.notesprout.android.data.updateColumns] for the notebook. Persists a moved/edited object's
 * payload columns by id; structural columns (parentId/order/createdAt) are left untouched.
 */
suspend fun CalendarDao.updateColumns(row: NotebookObject) = updateObjectColumns(
    row.id, row.x, row.y, row.width, row.height, row.text, row.color, row.strokeWidth, row.refId,
    row.level, row.lineStyle, row.orientation, row.dotSpacing, row.shapeType, row.centerX, row.centerY,
    row.rotationDeg, row.pointCount, row.contentW, row.contentH, row.linkTarget, row.chrome, row.flags,
    row.blob, row.updatedAt,
)

suspend fun ScratchpadDao.updateColumns(row: NotebookObject) = updateObjectColumns(
    row.id, row.x, row.y, row.width, row.height, row.text, row.color, row.strokeWidth, row.refId,
    row.level, row.lineStyle, row.orientation, row.dotSpacing, row.shapeType, row.centerX, row.centerY,
    row.rotationDeg, row.pointCount, row.contentW, row.contentH, row.linkTarget, row.chrome, row.flags,
    row.blob, row.updatedAt,
)
