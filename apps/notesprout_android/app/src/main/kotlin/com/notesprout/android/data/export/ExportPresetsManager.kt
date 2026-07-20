package com.notesprout.android.data.export

import android.content.Context
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Device-local store for the user's [ExportPreset] list.
 *
 * Backed by a single [android.content.SharedPreferences] key holding the `kotlinx.serialization`
 * JSON of the whole list — the same shape as
 * [com.notesprout.android.data.toolbar.ToolbarPreferencesManager]. Not in `notesprout.db`, not in
 * any `.soil`: presets are a device preference, not user content, and nothing in them is a secret.
 *
 * [load] is tolerant of malformed or absent JSON — it returns an empty list rather than throwing,
 * so a preset written by a future version with an unknown format enum can never wedge the export
 * screen.
 */
object ExportPresetsManager {

    private const val PREFS_NAME = "notesprout_export_prefs"
    private const val KEY_PRESETS = "presets"

    private val codec = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(ExportPreset.serializer())

    /** Every saved preset, in creation order. Empty when absent or unparseable. */
    fun load(context: Context): List<ExportPreset> {
        val raw = prefs(context).getString(KEY_PRESETS, null) ?: return emptyList()
        return runCatching { codec.decodeFromString(serializer, raw) }.getOrElse { emptyList() }
    }

    /** Append [preset] and persist. Returns the new list. */
    fun add(context: Context, preset: ExportPreset): List<ExportPreset> =
        (load(context) + preset).also { save(context, it) }

    /** Remove the preset with [id] and persist. Returns the new list. */
    fun delete(context: Context, id: String): List<ExportPreset> =
        load(context).filterNot { it.id == id }.also { save(context, it) }

    private fun save(context: Context, presets: List<ExportPreset>) {
        prefs(context).edit()
            .putString(KEY_PRESETS, codec.encodeToString(serializer, presets))
            .apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
