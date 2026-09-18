package com.symmetricalpalmtree.notesproutsn.data.prefs

import android.content.Context
import com.symmetricalpalmtree.notesproutsn.extension.SketchToolSettings

/**
 * The pure half of [SketchToolPrefs] (arc 44 / T2) — three stored integers, or the absence of any of
 * them, read as a [SketchToolSettings]. Context-free so every rule is JVM-tested, [SurfaceStackCodec]'s
 * argument for the same split.
 *
 * **Stored prefs are untrusted input**, exactly as a stored blob is: a file written by another
 * build, a half-written edit, or a value nothing in this build could have produced all have to read
 * as *nothing remembered* rather than as an exception on a Binder thread.
 */
object SketchToolCodec {

    /**
     * The three stored values as a settings parcel, or **null** when any of them is missing or the
     * trio would not make a legal [SketchToolSettings].
     *
     * The constructor is the validator — this does not repeat its bound, and deliberately does not
     * know the palette's either. The host stores indices and hands them back; **how many shades and
     * sizes exist is `:ext-sketch`'s to know**, and the face reads an index past the end of its own
     * list as its default. Clamping here would quietly rewrite the person's pick into a different
     * one every time the face's lists changed.
     */
    fun decode(tool: Int?, shade: Int?, size: Int?): SketchToolSettings? {
        if (tool == null || shade == null || size == null) return null
        return try {
            SketchToolSettings(tool, shade, size)
        } catch (_: IllegalArgumentException) {
            // A number that cannot be an index at all: the file is older, newer or damaged, and
            // "nothing remembered" is the one answer that is never wrong.
            null
        }
    }
}

/**
 * `SharedPreferences("sn_sketch_tools")` — which sketch tool was armed, and the pencil's shade and
 * size, the last time anybody drew on this device (arc 44 / T2). The two doors are
 * `ISketchHost.toolSettings()` / `putToolSettings()`, which the face asks at `begin` and pushes at
 * every pick.
 *
 * **Not `sn_tool`.** That name belongs to the dead `ToolPrefs` file, which
 * `SnApplication.LEGACY_TOOL_PREFS` **deletes at every process start** — storing anything under it
 * would be storing it into a file that is erased before the first showing.
 *
 * **One setting for every notebook, and device-local** (decision 6): it is a way of drawing, not a
 * property of a drawing, so it never goes in the `.soil`, is never backed up and is never restored —
 * [ChromePrefs]' argument exactly, and the reason an extension that writes nothing to disk can still
 * have its tools remembered across a process death.
 *
 * **Indices, never values.** Three small integers cross and three are stored; the greys and the
 * widths they name live in `:ext-sketch` and are never learned here.
 *
 * **Called from Binder threads.** `SharedPreferences` is thread-safe by contract, so no lock is
 * taken; [put] is one `edit()`…`apply()` of all three keys, so a reader can never see a new shade
 * against an old size.
 */
class SketchToolPrefs(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /**
     * What was last remembered, or **null** for nothing — a first showing, cleared app data, a
     * half-written trio, or a stored value this build cannot read as an index. Never throws: a
     * refusal here would cross the seam as a failure of a question that has a perfectly good
     * "nothing" answer.
     */
    fun get(): SketchToolSettings? = try {
        SketchToolCodec.decode(read(KEY_TOOL), read(KEY_SHADE), read(KEY_SIZE))
    } catch (_: ClassCastException) {
        // A key of another type entirely (a file from elsewhere): still just "nothing remembered".
        null
    }

    /** Replace what is remembered. One edit for the three keys — see the class doc. */
    fun put(settings: SketchToolSettings) {
        prefs.edit()
            .putInt(KEY_TOOL, settings.tool)
            .putInt(KEY_SHADE, settings.shade)
            .putInt(KEY_SIZE, settings.size)
            .apply()
    }

    /** `contains` first, because every integer is a legal stored value: there is no in-band number
     *  that could stand for "absent" without also being a pick somebody could make. */
    private fun read(key: String): Int? = if (prefs.contains(key)) prefs.getInt(key, 0) else null

    private companion object {
        /** Deliberately not `sn_tool` — see the class doc. */
        const val FILE = "sn_sketch_tools"
        const val KEY_TOOL = "tool"
        const val KEY_SHADE = "shade"
        const val KEY_SIZE = "size"
    }
}
