package com.notesprout.android.plugins

import android.util.Log
import com.notesprout.android.data.BaseObject
import com.notesprout.android.data.BoundingBox
import com.notesprout.android.data.SoilDatabase
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

// QuickJS set() requires interfaces, not concrete classes.
// Each namespace interface is what gets registered with the runtime;
// the companion class is the implementation.

interface IContextApi {
    fun getPluginId(): String
    fun getObjectId(): String
}

interface ICanvasApi {
    fun draw(objectJson: String)
    fun refresh()
    fun clear()
}

interface IDataApi {
    fun save(objectJson: String): String
    fun load(id: String): String?
    fun softDelete(id: String)
    // pluginId="" means no filter — JS must always pass both args
    fun getChildren(parentId: String, pluginId: String): String
}

interface IEventsApi {
    fun register(eventName: String, handlerName: String)
}

interface IExternalApi {
    fun stub()
}

// ─── Implementations ─────────────────────────────────────────────────────────

class ContextApi : IContextApi {
    var currentPluginId: String = ""
    var currentObjectId: String = ""

    override fun getPluginId(): String = currentPluginId
    override fun getObjectId(): String = currentObjectId
}

class CanvasApi : ICanvasApi {
    override fun draw(objectJson: String) {
        Log.d("NoteSprout", "canvas.draw called: $objectJson")
    }

    override fun refresh() {
        Log.d("NoteSprout", "canvas.refresh called")
    }

    override fun clear() {
        Log.d("NoteSprout", "canvas.clear called")
    }
}

class DataApi(private val database: SoilDatabase?) : IDataApi {

    // runBlocking is intentional: QuickJS is synchronous and always runs on a
    // background thread (IO dispatcher), so blocking here is safe and correct.

    override fun save(objectJson: String): String = runBlocking {
        val db = database ?: run {
            Log.w("NoteSprout", "data.save called but no database is open")
            return@runBlocking ""
        }
        val obj = baseObjectFromJson(objectJson)
        db.saveObject(obj)
        obj.id
    }

    override fun load(id: String): String? = runBlocking {
        val db = database ?: run {
            Log.w("NoteSprout", "data.load called but no database is open")
            return@runBlocking null
        }
        db.getObject(id)?.toJson()
    }

    override fun softDelete(id: String) {
        runBlocking {
            val db = database ?: run {
                Log.w("NoteSprout", "data.softDelete called but no database is open")
                return@runBlocking
            }
            db.softDelete(id)
        }
    }

    override fun getChildren(parentId: String, pluginId: String): String = runBlocking {
        val db = database ?: run {
            Log.w("NoteSprout", "data.getChildren called but no database is open")
            return@runBlocking "[]"
        }
        val filter = pluginId.takeIf { it.isNotEmpty() }
        val children = db.getChildren(parentId, filter)
        val arr = JSONArray()
        children.forEach { arr.put(JSONObject(it.toJson())) }
        arr.toString()
    }
}

class EventsApi : IEventsApi {
    override fun register(eventName: String, handlerName: String) {
        Log.d("NoteSprout", "events.register: $eventName -> $handlerName")
    }
}

class ExternalApi : IExternalApi {
    override fun stub() {
        Log.d("NoteSprout", "external API not yet implemented")
    }
}

// ─── Umbrella ─────────────────────────────────────────────────────────────────

class HostApi(database: SoilDatabase?) {
    val context = ContextApi()
    val canvas = CanvasApi()
    val data = DataApi(database)
    val events = EventsApi()
    val external = ExternalApi()
}

// ─── JSON helpers (private to this file) ─────────────────────────────────────

private fun baseObjectFromJson(json: String): BaseObject {
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

private fun BaseObject.toJson(): String = JSONObject().apply {
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
