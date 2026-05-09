package com.notesprout.android

import android.app.Application
import com.notesprout.android.data.DatabaseManager
import com.notesprout.android.plugins.PluginEngine

class NoteSproutApp : Application() {

    lateinit var pluginEngine: PluginEngine
    lateinit var databaseManager: DatabaseManager

    override fun onCreate() {
        super.onCreate()
        databaseManager = DatabaseManager(this)
        pluginEngine = PluginEngine(this, database = null)
    }
}
