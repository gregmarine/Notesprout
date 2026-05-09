package com.notesprout.android.plugins

import android.util.Log
import com.notesprout.android.data.BaseObject
import com.notesprout.android.data.baseObjectFromJson
import com.notesprout.android.data.toJson
import org.json.JSONObject

class PluginRunner(private val pluginEngine: PluginEngine) {

    suspend fun createObject(
        pluginId: String,
        parentId: String,
        data: Map<String, Any?>
    ): BaseObject {
        val dataJson = mapToJson(data)
        val result = pluginEngine.callFunction(pluginId, "createObject", parentId, dataJson)
            ?: throw IllegalStateException("Plugin $pluginId.createObject() returned null")
        return baseObjectFromJson(result as String)
    }

    suspend fun validate(pluginId: String, obj: BaseObject): Boolean {
        return try {
            val result = pluginEngine.callFunction(pluginId, "validate", obj.toJson())
            result as? Boolean ?: false
        } catch (e: Exception) {
            Log.w("NoteSprout", "validate() threw for $pluginId: ${e.message}")
            false
        }
    }

    suspend fun onLoad(pluginId: String, obj: BaseObject): BaseObject {
        return try {
            val result = pluginEngine.callFunction(pluginId, "onLoad", obj.toJson()) as? String
                ?: return obj
            baseObjectFromJson(result)
        } catch (e: Exception) {
            Log.w("NoteSprout", "onLoad() threw for $pluginId: ${e.message}")
            obj
        }
    }

    suspend fun onSave(pluginId: String, obj: BaseObject): BaseObject {
        return try {
            val result = pluginEngine.callFunction(pluginId, "onSave", obj.toJson()) as? String
                ?: return obj
            baseObjectFromJson(result)
        } catch (e: Exception) {
            Log.w("NoteSprout", "onSave() threw for $pluginId: ${e.message}")
            obj
        }
    }

    private fun mapToJson(data: Map<String, Any?>): String {
        val obj = JSONObject()
        data.forEach { (key, value) ->
            when (value) {
                null -> obj.put(key, JSONObject.NULL)
                is Int -> obj.put(key, value)
                is Long -> obj.put(key, value)
                is Double -> obj.put(key, value)
                is Boolean -> obj.put(key, value)
                is String -> obj.put(key, value)
                else -> obj.put(key, value.toString())
            }
        }
        return obj.toString()
    }
}
