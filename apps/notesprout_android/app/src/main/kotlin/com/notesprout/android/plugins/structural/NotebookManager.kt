package com.notesprout.android.plugins.structural

import com.notesprout.android.data.BaseObject
import com.notesprout.android.data.DatabaseManager
import com.notesprout.android.data.SoilDatabase
import com.notesprout.android.plugins.PluginEngine
import com.notesprout.android.plugins.PluginRunner

class NotebookManager(
    private val databaseManager: DatabaseManager,
    private val pluginEngine: PluginEngine
) {

    private var currentDatabase: SoilDatabase? = null
    private val pluginRunner = PluginRunner(pluginEngine)

    companion object {
        private const val NOTEBOOK_PLUGIN = "com.notesprout.structural.notebook"
        private const val PAGE_PLUGIN = "com.notesprout.structural.page"
        private const val LAYER_PLUGIN = "com.notesprout.structural.layer"
    }

    suspend fun createNotebook(
        name: String,
        pageWidth: Double,
        pageHeight: Double
    ): BaseObject {
        val sanitized = name.replace(Regex("[^A-Za-z0-9 \\-]"), "").trim().ifEmpty { "Untitled" }
        val db = databaseManager.createNotebook(sanitized)
        currentDatabase = db

        val notebookObj = pluginRunner.createObject(
            NOTEBOOK_PLUGIN,
            "",
            mapOf("name" to name)
        )
        require(pluginRunner.validate(NOTEBOOK_PLUGIN, notebookObj)) {
            "Invalid notebook object: ${notebookObj.data}"
        }
        db.saveObject(notebookObj)

        val pageObj = pluginRunner.createObject(
            PAGE_PLUGIN,
            notebookObj.id,
            mapOf("width" to pageWidth, "height" to pageHeight, "order" to 1)
        )
        require(pluginRunner.validate(PAGE_PLUGIN, pageObj)) {
            "Invalid page object: ${pageObj.data}"
        }
        db.saveObject(pageObj)

        val layerObj = pluginRunner.createObject(
            LAYER_PLUGIN,
            pageObj.id,
            mapOf("order" to 1)
        )
        require(pluginRunner.validate(LAYER_PLUGIN, layerObj)) {
            "Invalid layer object: ${layerObj.data}"
        }
        db.saveObject(layerObj)

        return notebookObj
    }

    suspend fun openNotebook(filePath: String): BaseObject? {
        val db = databaseManager.openNotebook(filePath)
        currentDatabase = db
        return db.getNotebook(NOTEBOOK_PLUGIN)
    }

    suspend fun getPages(notebookId: String): List<BaseObject> =
        currentDatabase?.getChildren(notebookId, PAGE_PLUGIN) ?: emptyList()

    suspend fun getLayers(pageId: String): List<BaseObject> =
        currentDatabase?.getChildren(pageId, LAYER_PLUGIN) ?: emptyList()
}
