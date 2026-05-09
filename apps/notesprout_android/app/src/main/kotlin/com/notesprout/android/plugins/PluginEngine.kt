package com.notesprout.android.plugins

import android.content.Context
import android.util.Log
import app.cash.quickjs.QuickJs
import com.notesprout.android.data.SoilDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.Executors

class PluginEngine(
    private val context: Context,
    private val database: SoilDatabase?
) {

    private lateinit var quickJs: QuickJs
    private lateinit var hostApi: HostApi
    private val loadedPlugins: MutableMap<String, String> = mutableMapOf()
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // QuickJS requires a thread with a large native stack (≥2MB) to evaluate JS functions.
    // Dispatchers.IO threads have small stacks (~512KB) and cause immediate stack overflow.
    // A dedicated single thread with 4MB stack is the correct fix.
    private val quickJsDispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(null, runnable, "quickjs-thread", 4 * 1024 * 1024)
    }.asCoroutineDispatcher()

    // Sets up the QuickJS runtime and exposes all host API namespaces.
    // Does NOT load any plugin JS files — PluginRegistry.initialize() does that.
    suspend fun initializeRuntime(): Unit = withContext(quickJsDispatcher) {
        quickJs = QuickJs.create()
        hostApi = HostApi(database)

        quickJs.set("context", IContextApi::class.java, hostApi.context)
        quickJs.set("canvas", ICanvasApi::class.java, hostApi.canvas)
        quickJs.set("data", IDataApi::class.java, hostApi.data)
        quickJs.set("events", IEventsApi::class.java, hostApi.events)
        quickJs.set("external", IExternalApi::class.java, hostApi.external)

        // __plugins__ is a plain JS global that each plugin registers itself into.
        // Avoids any globalThis compatibility issues with this QuickJS build.
        quickJs.evaluate("var __plugins__ = {};")
    }

    // Reads and evaluates one plugin JS file from assets.
    // The plugin's IIFE calls context.registerPlugin(PLUGIN_ID) as its last act,
    // which populates pendingRegistrations. This function clears and returns those
    // IDs so the caller (PluginRegistry) knows what was just loaded.
    // Deviation from spec: returns List<String> instead of taking pluginId as a
    // parameter — pluginId is unknown until after JS evaluation.
    suspend fun loadFromPath(assetPath: String): List<String> = withContext(quickJsDispatcher) {
        val source = try {
            context.assets.open(assetPath).bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            throw PluginNotFoundException("Plugin asset not found: $assetPath")
        }
        quickJs.evaluate(source)
        val pluginIds = hostApi.context.clearPendingRegistrations()
        Log.d("NoteSprout", "loaded $assetPath → $pluginIds")
        pluginIds.forEach { pluginId -> loadedPlugins[pluginId] = source }
        pluginIds
    }

    suspend fun callFunction(
        pluginId: String,
        functionName: String,
        vararg args: Any?
    ): Any? = withContext(quickJsDispatcher) {
        hostApi.context.currentPluginId = pluginId

        val argsStr = args.joinToString(", ") { serializeArg(it) }

        // __plugins__ is initialized in initializeRuntime and populated by each plugin's IIFE.
        // Lookup via bracket notation so dot-containing plugin IDs work correctly.
        val js = """
            (function() {
              var ns = __plugins__[${JSONObject.quote(pluginId)}];
              if (!ns) {
                throw new Error("NS_MISSING:" + ${JSONObject.quote(pluginId)} + " keys=" + Object.keys(__plugins__).join(","));
              }
              if (typeof ns[${JSONObject.quote(functionName)}] !== 'function') {
                return null;
              }
              return ns[${JSONObject.quote(functionName)}]($argsStr);
            })()
        """.trimIndent()

        try {
            quickJs.evaluate(js)
        } catch (e: Exception) {
            // Re-throw with the JS error message so device crash logs include it.
            throw RuntimeException("QuickJS $pluginId.$functionName: ${e.message}", e)
        }
    }

    fun destroy() {
        quickJs.close()
        coroutineScope.cancel()
        quickJsDispatcher.close()
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
