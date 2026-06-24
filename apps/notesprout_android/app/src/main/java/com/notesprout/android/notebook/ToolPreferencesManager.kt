package com.notesprout.android.notebook

import android.content.Context

enum class ActiveTool { PEN, ERASER, LASSO, LASSO_ERASER }

object ToolPreferencesManager {

    private const val PREFS_NAME = "notesprout_tool_prefs"
    private const val KEY_TOOL   = "active_tool"

    fun load(context: Context): ActiveTool {
        val name = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_TOOL, null) ?: return ActiveTool.PEN
        return runCatching { ActiveTool.valueOf(name) }.getOrDefault(ActiveTool.PEN)
    }

    fun save(context: Context, tool: ActiveTool) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_TOOL, tool.name)
            .apply()
    }
}
