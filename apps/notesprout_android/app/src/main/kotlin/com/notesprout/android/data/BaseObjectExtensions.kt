package com.notesprout.android.data

import android.content.ContentValues
import android.database.Cursor
import org.json.JSONObject
import java.util.UUID

fun BaseObject.toContentValues(): ContentValues = ContentValues().apply {
    put("id", id)
    put("parentId", parentId)
    put("pluginId", pluginId)
    put("boundingBox", boundingBox.toJson())
    put("order", order)
    put("createdAt", createdAt)
    put("updatedAt", updatedAt)
    if (deletedAt != null) put("deletedAt", deletedAt) else putNull("deletedAt")
    put("syncVersion", syncVersion)
    put("data", data)
}

fun Cursor.toBaseObject(): BaseObject = BaseObject(
    id = getString(getColumnIndexOrThrow("id")),
    parentId = getString(getColumnIndexOrThrow("parentId")),
    pluginId = getString(getColumnIndexOrThrow("pluginId")),
    boundingBox = BoundingBox.fromJson(getString(getColumnIndexOrThrow("boundingBox"))),
    order = getInt(getColumnIndexOrThrow("order")),
    createdAt = getLong(getColumnIndexOrThrow("createdAt")),
    updatedAt = getLong(getColumnIndexOrThrow("updatedAt")),
    deletedAt = if (isNull(getColumnIndexOrThrow("deletedAt"))) null
                else getLong(getColumnIndexOrThrow("deletedAt")),
    syncVersion = getLong(getColumnIndexOrThrow("syncVersion")),
    data = getString(getColumnIndexOrThrow("data"))
)

fun BaseObject.toJson(): String = JSONObject().apply {
    put("id", id)
    put("parentId", parentId)
    put("pluginId", pluginId)
    put("boundingBox", JSONObject(boundingBox.toJson()))
    put("order", order)
    put("createdAt", createdAt)
    put("updatedAt", updatedAt)
    if (deletedAt != null) put("deletedAt", deletedAt) else put("deletedAt", JSONObject.NULL)
    put("syncVersion", syncVersion)
    put("data", data)
}.toString()

fun baseObjectFromJson(json: String): BaseObject {
    val obj = JSONObject(json)
    val now = System.currentTimeMillis()
    val boundingBoxJson = if (obj.has("boundingBox") && !obj.isNull("boundingBox"))
        obj.get("boundingBox").toString()
    else
        BoundingBox.empty().toJson()
    return BaseObject(
        id = obj.optString("id").ifEmpty { UUID.randomUUID().toString() },
        parentId = obj.optString("parentId", ""),
        pluginId = obj.optString("pluginId", ""),
        boundingBox = BoundingBox.fromJson(boundingBoxJson),
        order = obj.optInt("order", 0),
        createdAt = obj.optLong("createdAt", now),
        updatedAt = obj.optLong("updatedAt", now),
        deletedAt = if (obj.isNull("deletedAt")) null else obj.optLong("deletedAt"),
        syncVersion = obj.optLong("syncVersion", 0),
        data = obj.optString("data", "{}")
    )
}
