package com.notesprout.android.data

import com.notesprout.android.NotesproutClipboard
import kotlinx.serialization.json.Json
import com.notesprout.android.data.StickyNoteRender

private val clipCodec = Json { ignoreUnknownKeys = true; explicitNulls = false }

fun NotesproutClipboard.ClipboardContent.toPayload(
    sourceNotebookId: String,
    sourceEncrypted: Boolean,
): ClipboardPayload {
    val items = mutableListOf<ClipItem>()
    strokes.forEach { s ->
        items += ClipItem(
            TYPE_STROKE, BoundingBoxData.from(s.boundingBox),
            clipCodec.encodeToString(LiveStroke.serializer(), s),
        )
    }
    headings.forEach { h ->
        items += ClipItem(
            TYPE_HEADING, BoundingBoxData.from(h.boundingBox),
            clipCodec.encodeToString(HeadingStroke.serializer(), h),
        )
    }
    textObjects.forEach { t ->
        items += ClipItem(
            TYPE_TEXT, BoundingBoxData.from(t.boundingBox),
            clipCodec.encodeToString(TextRender.serializer(), t),
        )
    }
    lineObjects.forEach { l ->
        items += ClipItem(
            TYPE_LINE, BoundingBoxData.from(l.boundingBox),
            clipCodec.encodeToString(LineRender.serializer(), l),
        )
    }
    links.forEach { lk ->
        items += ClipItem(
            TYPE_LINK, BoundingBoxData.from(lk.boundingBox),
            clipCodec.encodeToString(LinkRender.serializer(), lk),
        )
    }
    stickyNotes.forEach { sn ->
        items += ClipItem(
            TYPE_STICKY_NOTE, BoundingBoxData.from(sn.boundingBox),
            clipCodec.encodeToString(StickyNoteRender.serializer(), sn),
        )
    }
    shapeObjects.forEach { shape ->
        items += ClipItem(
            TYPE_SHAPE, BoundingBoxData.from(shape.boundingBox),
            clipCodec.encodeToString(ShapeRender.serializer(), shape),
        )
    }
    return ClipboardPayload(
        items = items,
        boundingBox = BoundingBoxData.from(boundingBox),
        sourceNotebookId = sourceNotebookId,
        sourceEncrypted = sourceEncrypted,
        copiedAt = System.currentTimeMillis(),
    )
}

fun ClipboardPayload.toClipboardContent(): NotesproutClipboard.ClipboardContent? {
    if (items.isEmpty()) return null
    val strokes      = mutableListOf<LiveStroke>()
    val headings     = mutableListOf<HeadingStroke>()
    val textObjects  = mutableListOf<TextRender>()
    val lineObjects  = mutableListOf<LineRender>()
    val links        = mutableListOf<LinkRender>()
    val stickyNotes  = mutableListOf<StickyNoteRender>()
    val shapes       = mutableListOf<ShapeRender>()
    for (item in items) {
        when (item.type) {
            TYPE_STROKE       -> strokes.add(clipCodec.decodeFromString(LiveStroke.serializer(), item.data))
            TYPE_HEADING      -> headings.add(clipCodec.decodeFromString(HeadingStroke.serializer(), item.data))
            TYPE_TEXT         -> textObjects.add(clipCodec.decodeFromString(TextRender.serializer(), item.data))
            TYPE_LINE         -> lineObjects.add(clipCodec.decodeFromString(LineRender.serializer(), item.data))
            TYPE_LINK         -> links.add(clipCodec.decodeFromString(LinkRender.serializer(), item.data))
            TYPE_STICKY_NOTE  -> stickyNotes.add(clipCodec.decodeFromString(StickyNoteRender.serializer(), item.data))
            TYPE_SHAPE        -> shapes.add(clipCodec.decodeFromString(ShapeRender.serializer(), item.data))
        }
    }
    if (strokes.isEmpty() && headings.isEmpty() && textObjects.isEmpty() && lineObjects.isEmpty() && links.isEmpty() && stickyNotes.isEmpty() && shapes.isEmpty()) return null
    return NotesproutClipboard.ClipboardContent(
        strokes = strokes,
        headings = headings,
        boundingBox = boundingBox.toRectF(),
        textObjects = textObjects,
        lineObjects = lineObjects,
        links = links,
        stickyNotes = stickyNotes,
        shapeObjects = shapes,
    )
}
