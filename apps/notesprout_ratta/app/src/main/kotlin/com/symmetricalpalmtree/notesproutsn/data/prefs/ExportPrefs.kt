package com.symmetricalpalmtree.notesproutsn.data.prefs

import android.content.Context

/**
 * `SharedPreferences("sn_export")` — the exporter package the last **successful** export used
 * (arc 18, the user's 2026-08-30 call). With more than one exporter installed the chooser's
 * default was discovery order, which is PackageManager's and means nothing; the format a person
 * exported to last is the one they most likely want again. "Used", not merely tapped: a pick
 * abandoned at the picker never becomes the default, only an export that finished does.
 *
 * A package name, not a format label — the pick is re-matched against what is actually installed
 * at discovery, so a remembered exporter that has gone simply falls back to the first listed.
 */
class ExportPrefs(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var lastExporter: String?
        get() = prefs.getString(KEY_LAST_EXPORTER, null)
        set(value) { prefs.edit().putString(KEY_LAST_EXPORTER, value).apply() }

    private companion object {
        const val FILE = "sn_export"
        const val KEY_LAST_EXPORTER = "lastExporter"
    }
}
