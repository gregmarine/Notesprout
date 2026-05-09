package com.notesprout.android.plugins

object PluginRegistry {

    private val plugins = listOf(
        PluginManifest(
            pluginId = "com.notesprout.structural.notebook",
            name = "Notebook",
            version = 1,
            type = PluginType.STRUCTURAL,
            assetPath = "plugins/structural/notebook/index.js"
        ),
        PluginManifest(
            pluginId = "com.notesprout.structural.page",
            name = "Page",
            version = 1,
            type = PluginType.STRUCTURAL,
            assetPath = "plugins/structural/page/index.js"
        ),
        PluginManifest(
            pluginId = "com.notesprout.structural.layer",
            name = "Layer",
            version = 1,
            type = PluginType.STRUCTURAL,
            assetPath = "plugins/structural/layer/index.js"
        ),
        PluginManifest(
            pluginId = "com.notesprout.tools.gel_pen",
            name = "Gel Pen",
            version = 1,
            type = PluginType.TOOL,
            assetPath = "plugins/tools/gel_pen/index.js"
        ),
        PluginManifest(
            pluginId = "com.notesprout.tools.eraser",
            name = "Eraser",
            version = 1,
            type = PluginType.TOOL,
            assetPath = "plugins/tools/eraser/index.js"
        )
    )

    fun getAll(): List<PluginManifest> = plugins

    fun getById(pluginId: String): PluginManifest? = plugins.find { it.pluginId == pluginId }

    fun getAllByType(type: PluginType): List<PluginManifest> = plugins.filter { it.type == type }
}
