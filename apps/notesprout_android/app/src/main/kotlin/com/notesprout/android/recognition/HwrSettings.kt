package com.notesprout.android.recognition

import android.content.Context

/**
 * Preferences for the handwriting-engine choice and TrOCR model state.
 * ML Kit remains the default engine; TrOCR is opt-in via the settings toggle
 * and only takes effect when a model bundle is installed.
 */
object HwrSettings {

    const val ENGINE_MLKIT = "mlkit"
    const val ENGINE_TROCR = "trocr"

    private const val PREFS = "hwr_settings"
    private const val KEY_ENGINE = "engine"
    private const val KEY_ACTIVE_MODEL_VERSION = "active_model_version"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun engine(context: Context): String =
        prefs(context).getString(KEY_ENGINE, ENGINE_MLKIT) ?: ENGINE_MLKIT

    fun setEngine(context: Context, engine: String) {
        prefs(context).edit().putString(KEY_ENGINE, engine).apply()
    }

    /** versionId of the active TrOCR model bundle, or null when none installed. */
    fun activeModelVersion(context: Context): String? =
        prefs(context).getString(KEY_ACTIVE_MODEL_VERSION, null)

    fun setActiveModelVersion(context: Context, versionId: String?) {
        prefs(context).edit().apply {
            if (versionId == null) remove(KEY_ACTIVE_MODEL_VERSION)
            else putString(KEY_ACTIVE_MODEL_VERSION, versionId)
        }.apply()
    }
}
