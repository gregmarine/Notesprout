package com.notesprout.android.notebook

import android.content.Context

object ScratchpadPreferences {

    private const val PREFS_NAME = "notesprout_scratchpad_prefs"
    private const val KEY_CURRENT_PAGE_INDEX = "current_page_index"

    fun loadCurrentPageIndex(context: Context): Int =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_CURRENT_PAGE_INDEX, 0)

    fun saveCurrentPageIndex(context: Context, index: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_CURRENT_PAGE_INDEX, index)
            .apply()
    }
}
