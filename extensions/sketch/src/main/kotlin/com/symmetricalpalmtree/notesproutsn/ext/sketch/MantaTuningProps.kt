package com.symmetricalpalmtree.notesproutsn.ext.sketch

import android.util.Log
import com.symmetricalpalmtree.gpaper.ratta.RattaTuning

/**
 * The Manta pencil walk's `setprop` door (branch `sketch-manta`) — **debug builds only, and
 * temporary**: it goes when g-paper's `RattaTuning` does, once the Manta's numbers freeze.
 *
 * ```
 * adb shell setprop debug.gpaper.pencil_emr 160            # firmware live-line size; "" = engine default
 * adb shell setprop debug.gpaper.pencil_bake_pressure 0.4  # "" = engine default (0.5)
 * ```
 *
 * Read once per showing, before the paper view exists — leave the face and come back to try the
 * next candidate. Unset or unparseable leaves the engine's own constant in place.
 */
internal object MantaTuningProps {

    private const val TAG = "MantaTuningProps"

    fun apply() {
        RattaTuning.pencilEmr = getprop("debug.gpaper.pencil_emr")?.toIntOrNull()
        RattaTuning.pencilBakePressure = getprop("debug.gpaper.pencil_bake_pressure")?.toFloatOrNull()
        Log.i(TAG, "pencilEmr=${RattaTuning.pencilEmr} pencilBakePressure=${RattaTuning.pencilBakePressure}")
    }

    private fun getprop(name: String): String? = try {
        val process = ProcessBuilder("getprop", name).redirectErrorStream(true).start()
        val value = process.inputStream.bufferedReader().use { it.readText() }.trim()
        process.waitFor()
        value.ifEmpty { null }
    } catch (e: Exception) {
        Log.w(TAG, "getprop $name failed", e)
        null
    }
}
