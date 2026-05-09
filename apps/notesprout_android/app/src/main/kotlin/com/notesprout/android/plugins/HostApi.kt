package com.notesprout.android.plugins

import android.util.Log
import com.notesprout.android.BuildConfig
import com.notesprout.android.canvas.CanvasDelegate
import com.notesprout.android.data.BaseObject
import com.notesprout.android.data.SoilDatabase
import com.notesprout.android.data.baseObjectFromJson
import com.notesprout.android.data.toJson
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
    fun newId(): String
    fun now(): Double
    fun getAppVersion(): String
    fun getCurrentPageId(): String
    fun getCurrentLayerId(): String
    // Called from each plugin's IIFE at load time so the registry can discover its pluginId.
    fun registerPlugin(pluginId: String)
}

interface ICanvasApi {
    fun draw(objectJson: String)
    fun drawStroke(pointsJson: String, styleJson: String)
    fun refresh()
    fun clear()
    fun redrawAll(strokesJson: String)
}

interface IDataApi {
    fun save(objectJson: String): String
    fun load(id: String): String?
    fun softDelete(id: String)
    // pluginId="" means no filter — JS must always pass both args
    fun getChildren(parentId: String, pluginId: String): String
    // Returns all non-deleted children of parentId regardless of plugin
    fun query(parentId: String): String
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
    var pageId: String = ""
    var layerId: String = ""

    // Populated by each plugin's context.registerPlugin(PLUGIN_ID) call at load time.
    // clearPendingRegistrations() is intentionally NOT on IContextApi: QuickJS-Android
    // proxies every interface method and List<String> is not a JS-compatible return type.
    private val pendingRegistrations: MutableList<String> = mutableListOf()

    override fun getPluginId(): String = currentPluginId
    override fun getObjectId(): String = currentObjectId
    override fun newId(): String = UUID.randomUUID().toString()
    override fun now(): Double = System.currentTimeMillis().toDouble()
    override fun getAppVersion(): String = BuildConfig.VERSION_NAME
    override fun getCurrentPageId(): String = pageId
    override fun getCurrentLayerId(): String = layerId

    override fun registerPlugin(pluginId: String) {
        pendingRegistrations.add(pluginId)
        Log.d("NoteSprout", "plugin registered: $pluginId")
    }

    fun clearPendingRegistrations(): List<String> {
        val snapshot = pendingRegistrations.toList()
        pendingRegistrations.clear()
        return snapshot
    }
}

class CanvasApi : ICanvasApi {
    var delegate: CanvasDelegate? = null

    override fun draw(objectJson: String) {
        Log.d("NoteSprout", "canvas.draw called: $objectJson")
    }

    override fun drawStroke(pointsJson: String, styleJson: String) {
        delegate?.drawStroke(pointsJson, styleJson)
            ?: Log.d("NoteSprout", "canvas.drawStroke called but no delegate attached")
    }

    override fun refresh() {
        delegate?.refresh()
            ?: Log.d("NoteSprout", "canvas.refresh called but no delegate attached")
    }

    override fun clear() {
        delegate?.clear()
            ?: Log.d("NoteSprout", "canvas.clear called but no delegate attached")
    }

    override fun redrawAll(strokesJson: String) {
        delegate?.redrawAll(strokesJson)
            ?: Log.d("NoteSprout", "canvas.redrawAll called but no delegate attached")
        delegate?.refresh()
    }
}

class DataApi(var database: SoilDatabase?) : IDataApi {

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

    override fun query(parentId: String): String = getChildren(parentId, "")
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
