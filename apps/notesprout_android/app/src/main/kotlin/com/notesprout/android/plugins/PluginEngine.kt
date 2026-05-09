package com.notesprout.android.plugins

import android.content.Context
import android.util.Log
import app.cash.quickjs.QuickJs
import com.notesprout.android.data.SoilDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import org.json.JSONObject

class PluginEngine(
    private val context: Context,
    private val database: SoilDatabase?
) {

    private lateinit var quickJs: QuickJs
    private lateinit var hostApi: HostApi
    private val loadedPlugins: MutableMap<String, String> = mutableMapOf()
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun initialize() {
        quickJs = QuickJs.create()
        hostApi = HostApi(database)

        // QuickJs.set() requires an interface type, not a concrete class.
        // We pass each namespace interface so QuickJS can generate a JS proxy.
        quickJs.set("context", IContextApi::class.java, hostApi.context)
        quickJs.set("canvas", ICanvasApi::class.java, hostApi.canvas)
        quickJs.set("data", IDataApi::class.java, hostApi.data)
        quickJs.set("events", IEventsApi::class.java, hostApi.events)
        quickJs.set("external", IExternalApi::class.java, hostApi.external)

        PluginRegistry.getAll().forEach { manifest ->
            try {
                loadPlugin(manifest)
                Log.d("NoteSprout", "loaded plugin ${manifest.pluginId} v${manifest.version}")
            } catch (e: PluginNotFoundException) {
                Log.e("NoteSprout", "failed to load plugin ${manifest.pluginId}: ${e.message}")
            }
        }
    }

    private fun loadPlugin(manifest: PluginManifest): String {
        val source = try {
            context.assets.open(manifest.assetPath).bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            throw PluginNotFoundException("Plugin not found: ${manifest.assetPath}")
        }
        loadedPlugins[manifest.pluginId] = source
        quickJs.evaluate(source)
        return source
    }

    suspend fun callFunction(
        pluginId: String,
        functionName: String,
        vararg args: Any?
    ): Any? = withContext(Dispatchers.IO) {
        hostApi.context.currentPluginId = pluginId
        Log.d("NoteSprout", "calling $pluginId.$functionName")

        val argsStr = args.joinToString(", ") { serializeArg(it) }

        // Plugins expose functions via globalThis[pluginId] namespace (see index.js stubs).
        // A bare functionName() call would not work because each plugin wraps its functions
        // in an IIFE to avoid name collisions across plugins in the same QuickJS runtime.
        val js = """
            (function() {
              var ns = globalThis["$pluginId"];
              if (ns && typeof ns["$functionName"] === 'function') {
                return ns["$functionName"]($argsStr);
              }
              return null;
            })()
        """.trimIndent()

        try {
            quickJs.evaluate(js)
        } catch (e: Exception) {
            Log.e("NoteSprout", "QuickJS error in $pluginId.$functionName: ${e.message}")
            null
        }
    }

    fun destroy() {
        quickJs.close()
        coroutineScope.cancel()
        loadedPlugins.clear()
    }

    private fun serializeArg(arg: Any?): String = when (arg) {
        null -> "null"
        is String -> JSONObject.quote(arg)
        is Number -> arg.toString()
        is Boolean -> arg.toString()
        else -> JSONObject.quote(arg.toString())
    }
}
