package com.notesprout.android.data

import android.content.ContentValues
import android.database.Cursor

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
