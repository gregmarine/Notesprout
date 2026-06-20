package com.notesprout.android.data.toolbar

import android.content.Context
import com.notesprout.android.notebook.ToolbarButtonRegistry
import kotlinx.serialization.json.Json

/**
 * Device-local store for the global [ToolbarConfig].
 *
 * Backed by a single [android.content.SharedPreferences] key ([KEY_CONFIG]) holding the
 * `kotlinx.serialization` JSON of one [ToolbarConfig]. Mirrors
 * [com.notesprout.android.data.recents.RecentsManager] and
 * [com.notesprout.android.sort.SortPreferencesManager] — not in `notesprout.db`, not in any
 * `.soil` file.
 *
 * [load] is tolerant of malformed/absent JSON → returns defaults.
 */
object ToolbarPreferencesManager {

    private const val PREFS_NAME = "notesprout_toolbar_prefs"
    private const val KEY_CONFIG = "config"

    private val codec = Json { ignoreUnknownKeys = true }

    /** The persisted config, or [ToolbarConfig] defaults when absent or unparseable. */
    fun load(context: Context): ToolbarConfig {
        val raw = prefs(context).getString(KEY_CONFIG, null) ?: return ToolbarConfig()
        val config = runCatching { codec.decodeFromString(ToolbarConfig.serializer(), raw) }
            .getOrElse { ToolbarConfig() }
        // Insert registry keys missing from the persisted order (e.g. buttons added in later app
        // updates). Appended to the end so the existing layout is unchanged. Persisted back so this
        // runs only once per new button rather than on every open.
        val missingKeys = ToolbarButtonRegistry.DEFAULT_ORDER.filter { it !in config.order }
        if (missingKeys.isEmpty()) return config
        val merged = config.copy(order = config.order + missingKeys)
        save(context, merged)
        return merged
    }

    /** Persist [config] as the single global toolbar configuration. */
    fun save(context: Context, config: ToolbarConfig) {
        prefs(context).edit()
            .putString(KEY_CONFIG, codec.encodeToString(ToolbarConfig.serializer(), config))
            .apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
