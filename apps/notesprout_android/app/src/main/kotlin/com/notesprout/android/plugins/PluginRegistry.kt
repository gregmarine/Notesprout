package com.notesprout.android.plugins

import android.content.Context
import android.util.Log
import org.json.JSONObject

object PluginRegistry {

    // The only two things hardcoded in Kotlin: the root asset path and the known
    // type folder names. Every plugin identity comes from the JS files themselves.
    private const val PLUGINS_ROOT = "plugins"
    private val TYPE_FOLDERS = listOf("structural", "tools")

    // Folder name → PluginType fallback used when a JS plugin omits "type" in its manifest.
    private val FOLDER_TYPE_MAP = mapOf(
        "structural" to PluginType.STRUCTURAL,
        "tools" to PluginType.TOOL
    )

    private val manifests = mutableMapOf<String, PluginManifest>()

    fun getAll(): List<PluginManifest> = manifests.values.toList()

    fun getById(pluginId: String): PluginManifest? = manifests[pluginId]

    fun getAllByType(type: PluginType): List<PluginManifest> =
        manifests.values.filter { it.type == type }

    // Discovers, loads, and registers every plugin found under assets/plugins/.
    // For each type folder (structural, tools), lists subfolders and looks for index.js.
    // Each plugin's IIFE calls context.registerPlugin(PLUGIN_ID) which tells us its ID.
    // We then call getManifest() to get the authoritative name/version/type from JS.
    suspend fun initialize(context: Context, pluginEngine: PluginEngine) {
        manifests.clear()
        var discoveredCount = 0

        for (typeFolder in TYPE_FOLDERS) {
            val subfolders = try {
                context.assets.list("$PLUGINS_ROOT/$typeFolder") ?: emptyArray()
            } catch (e: Exception) {
                Log.w("NoteSprout", "asset folder $PLUGINS_ROOT/$typeFolder not found: ${e.message}")
                continue
            }

            for (subfolder in subfolders) {
                val assetPath = "$PLUGINS_ROOT/$typeFolder/$subfolder/index.js"

                // Verify the file exists before handing it to the engine.
                try {
                    context.assets.open(assetPath).close()
                } catch (e: Exception) {
                    Log.w("NoteSprout", "no index.js at $assetPath — skipping")
                    continue
                }

                // Evaluate the JS. The plugin's IIFE calls context.registerPlugin(PLUGIN_ID)
                // as its last statement, which populates pendingRegistrations in ContextApi.
                // loadFromPath clears and returns those IDs.
                val pluginIds = try {
                    pluginEngine.loadFromPath(assetPath)
                } catch (e: Exception) {
                    Log.w("NoteSprout", "failed to evaluate $assetPath: ${e.message}")
                    continue
                }

                if (pluginIds.isEmpty()) {
                    Log.w("NoteSprout", "$assetPath evaluated but called no context.registerPlugin()")
                    continue
                }

                for (pluginId in pluginIds) {
                    try {
                        val result = pluginEngine.callFunction(pluginId, "getManifest") as? String
                        if (result == null) {
                            Log.w("NoteSprout", "$pluginId.getManifest() returned null — skipping")
                            continue
                        }

                        val json = JSONObject(result)
                        val typeStr = json.optString("type", "")
                        val type = if (typeStr.isNotEmpty()) {
                            try {
                                PluginType.valueOf(typeStr.uppercase())
                            } catch (e: IllegalArgumentException) {
                                FOLDER_TYPE_MAP[typeFolder] ?: PluginType.TOOL
                            }
                        } else {
                            FOLDER_TYPE_MAP[typeFolder] ?: PluginType.TOOL
                        }

                        val manifest = PluginManifest(
                            pluginId = pluginId,
                            name = json.optString("name", subfolder),
                            version = json.optInt("version", 1),
                            type = type,
                            assetPath = assetPath
                        )
                        manifests[pluginId] = manifest
                        discoveredCount++
                        Log.d("NoteSprout", "$pluginId v${manifest.version} [${manifest.type}]")
                    } catch (e: Exception) {
                        Log.w("NoteSprout", "failed to register manifest for $pluginId: ${e.message}")
                    }
                }
            }
        }

        Log.d("NoteSprout", "discovered $discoveredCount plugins")
    }
}
