package com.notesprout.android.plugins

data class PluginManifest(
    val pluginId: String,
    val name: String,
    val version: Int,
    val type: PluginType,
    val assetPath: String
)
