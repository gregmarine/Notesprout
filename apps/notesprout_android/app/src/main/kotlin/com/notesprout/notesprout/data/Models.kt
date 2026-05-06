package com.notesprout.notesprout.data

import android.content.ContentValues
import android.database.Cursor
import org.json.JSONArray
import org.json.JSONObject

data class NotebookMeta(
    val id: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    val syncVersion: Int = 0,
    val pageWidth: Double,
    val pageHeight: Double
) {
    fun toContentValues() = ContentValues().apply {
        put("id", id)
        put("name", name)
        put("createdAt", createdAt)
        put("updatedAt", updatedAt)
        put("syncVersion", syncVersion)
        put("pageWidth", pageWidth)
        put("pageHeight", pageHeight)
    }

    companion object {
        fun fromCursor(c: Cursor) = NotebookMeta(
            id = c.getString(c.getColumnIndexOrThrow("id")),
            name = c.getString(c.getColumnIndexOrThrow("name")),
            createdAt = c.getLong(c.getColumnIndexOrThrow("createdAt")),
            updatedAt = c.getLong(c.getColumnIndexOrThrow("updatedAt")),
            syncVersion = c.getInt(c.getColumnIndexOrThrow("syncVersion")),
            pageWidth = c.getDouble(c.getColumnIndexOrThrow("pageWidth")),
            pageHeight = c.getDouble(c.getColumnIndexOrThrow("pageHeight"))
        )
    }
}

data class PageModel(
    val id: String,
    val parentId: String?,
    val type: String = "page",
    val subtype: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
    val pageNumber: Int,
    val width: Double,
    val height: Double
) {
    fun toContentValues() = ContentValues().apply {
        put("id", id)
        put("parentId", parentId)
        put("type", type)
        put("subtype", subtype)
        put("createdAt", createdAt)
        put("updatedAt", updatedAt)
        if (deletedAt != null) put("deletedAt", deletedAt) else putNull("deletedAt")
        put("pageNumber", pageNumber)
        put("width", width)
        put("height", height)
    }

    companion object {
        fun fromCursor(c: Cursor) = PageModel(
            id = c.getString(c.getColumnIndexOrThrow("id")),
            parentId = c.getString(c.getColumnIndexOrThrow("parentId")),
            type = c.getString(c.getColumnIndexOrThrow("type")),
            subtype = c.getString(c.getColumnIndexOrThrow("subtype")),
            createdAt = c.getLong(c.getColumnIndexOrThrow("createdAt")),
            updatedAt = c.getLong(c.getColumnIndexOrThrow("updatedAt")),
            deletedAt = if (c.isNull(c.getColumnIndexOrThrow("deletedAt"))) null
                        else c.getLong(c.getColumnIndexOrThrow("deletedAt")),
            pageNumber = c.getInt(c.getColumnIndexOrThrow("pageNumber")),
            width = c.getDouble(c.getColumnIndexOrThrow("width")),
            height = c.getDouble(c.getColumnIndexOrThrow("height"))
        )
    }
}

data class LayerModel(
    val id: String,
    val parentId: String?,
    val type: String = "layer",
    val subtype: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
    val isLocked: Boolean = false,
    val isVisible: Boolean = true,
    val opacity: Double = 1.0
) {
    fun toContentValues() = ContentValues().apply {
        put("id", id)
        put("parentId", parentId)
        put("type", type)
        put("subtype", subtype)
        put("createdAt", createdAt)
        put("updatedAt", updatedAt)
        if (deletedAt != null) put("deletedAt", deletedAt) else putNull("deletedAt")
        put("isLocked", if (isLocked) 1 else 0)
        put("isVisible", if (isVisible) 1 else 0)
        put("opacity", opacity)
    }

    companion object {
        fun fromCursor(c: Cursor) = LayerModel(
            id = c.getString(c.getColumnIndexOrThrow("id")),
            parentId = c.getString(c.getColumnIndexOrThrow("parentId")),
            type = c.getString(c.getColumnIndexOrThrow("type")),
            subtype = c.getString(c.getColumnIndexOrThrow("subtype")),
            createdAt = c.getLong(c.getColumnIndexOrThrow("createdAt")),
            updatedAt = c.getLong(c.getColumnIndexOrThrow("updatedAt")),
            deletedAt = if (c.isNull(c.getColumnIndexOrThrow("deletedAt"))) null
                        else c.getLong(c.getColumnIndexOrThrow("deletedAt")),
            isLocked = c.getInt(c.getColumnIndexOrThrow("isLocked")) != 0,
            isVisible = c.getInt(c.getColumnIndexOrThrow("isVisible")) != 0,
            opacity = c.getDouble(c.getColumnIndexOrThrow("opacity"))
        )
    }
}

data class StrokePoint(
    val x: Double,
    val y: Double,
    val pressure: Double,
    val tilt: Double,
    val timestamp: Long
) {
    fun toJson() = JSONObject().apply {
        put("x", x)
        put("y", y)
        put("pressure", pressure)
        put("tilt", tilt)
        put("timestamp", timestamp)
    }

    companion object {
        fun fromJson(j: JSONObject) = StrokePoint(
            x = j.getDouble("x"),
            y = j.getDouble("y"),
            pressure = j.getDouble("pressure"),
            tilt = j.getDouble("tilt"),
            timestamp = j.getLong("timestamp")
        )
    }
}

data class StrokeModel(
    val id: String,
    val parentId: String?,
    val type: String = "stroke",
    val subtype: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null,
    val points: List<StrokePoint>,
    val color: Int,
    val width: Double
) {
    fun toContentValues(): ContentValues {
        val pointsJson = JSONArray().also { arr -> points.forEach { arr.put(it.toJson()) } }.toString()
        return ContentValues().apply {
            put("id", id)
            put("parentId", parentId)
            put("type", type)
            put("subtype", subtype)
            put("createdAt", createdAt)
            put("updatedAt", updatedAt)
            if (deletedAt != null) put("deletedAt", deletedAt) else putNull("deletedAt")
            put("points", pointsJson)
            put("color", color)
            put("width", width)
        }
    }

    companion object {
        fun fromCursor(c: Cursor): StrokeModel {
            val arr = JSONArray(c.getString(c.getColumnIndexOrThrow("points")))
            val points = (0 until arr.length()).map { StrokePoint.fromJson(arr.getJSONObject(it)) }
            return StrokeModel(
                id = c.getString(c.getColumnIndexOrThrow("id")),
                parentId = c.getString(c.getColumnIndexOrThrow("parentId")),
                type = c.getString(c.getColumnIndexOrThrow("type")),
                subtype = c.getString(c.getColumnIndexOrThrow("subtype")),
                createdAt = c.getLong(c.getColumnIndexOrThrow("createdAt")),
                updatedAt = c.getLong(c.getColumnIndexOrThrow("updatedAt")),
                deletedAt = if (c.isNull(c.getColumnIndexOrThrow("deletedAt"))) null
                            else c.getLong(c.getColumnIndexOrThrow("deletedAt")),
                points = points,
                color = c.getInt(c.getColumnIndexOrThrow("color")),
                width = c.getDouble(c.getColumnIndexOrThrow("width"))
            )
        }
    }
}
