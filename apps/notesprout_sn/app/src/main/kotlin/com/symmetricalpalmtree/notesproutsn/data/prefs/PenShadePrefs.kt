package com.symmetricalpalmtree.notesproutsn.data.prefs

import android.content.Context
import com.symmetricalpalmtree.notesproutsn.core.InkTones

/**
 * The pure half of [PenShadePrefs] (arc 49 / P4): a stored integer, or none, read as a level on the
 * sixteen-tone ladder. Context-free so the rule is JVM-tested, [SketchToolCodec]'s split.
 *
 * **Stored prefs are untrusted input**: a file written by another build, or a value nothing in this
 * build could have produced, reads as **black** — the pen every writing face opened with before
 * P4 — never as an exception and never as a tone off the ladder. This one *does* fold to a level,
 * unlike the sketch's codec, because the host is the one that arms the pen with it: the tone
 * comes from `InkTones` here, so the ladder is the host's to know.
 */
object PenShadeCodec {

    /** [stored] when this build offers that level; otherwise [InkTones.BLACK]. Null = nothing remembered = black. */
    fun decode(stored: Int?): Int = if (stored == null) InkTones.BLACK else InkTones.levelOrElse(stored, InkTones.BLACK)
}

/**
 * `SharedPreferences("sn_pen_shade")` — the writing pen's shade on this device (arc 49 / P4, the
 * user's decisions 4 and 6): one level on the sixteen-tone ladder, for the notebook, the sticky
 * editor, the scratch pad and the calendar alike. Default **black** (level 0), the pen every one
 * of them had before P4.
 *
 * **One shade, deliberately device-wide** rather than per-notebook or per-screen: a grey pen is a
 * way of working, not a property of a page — [ChromePrefs]' argument exactly, and the user's own
 * word ("grey picked in the notebook is grey in the pad"). The two host screens read and write it
 * here; the two extension screens receive it as a launch extra and echo their final level on the
 * result Intent, and the host writes it here. Device-local: never in the `.soil`, never backed up,
 * never restored.
 *
 * **Not `sn_tool`** — that name belongs to the dead R3 `ToolPrefs` file, which `SnApplication`
 * deletes at every process start (the sketch prefs' note); and not `sn_sketch_tools`, whose four
 * ints are the sketch face's own and cross a Binder.
 */
class PenShadePrefs(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** The remembered level, folded onto the ladder ([PenShadeCodec]); a write stores it as given. */
    var level: Int
        get() = try {
            PenShadeCodec.decode(if (prefs.contains(KEY_LEVEL)) prefs.getInt(KEY_LEVEL, InkTones.BLACK) else null)
        } catch (_: ClassCastException) {
            // A key of another type entirely (a file from elsewhere): nothing remembered, black.
            InkTones.BLACK
        }
        set(value) { prefs.edit().putInt(KEY_LEVEL, value).apply() }

    private companion object {
        const val FILE = "sn_pen_shade"
        const val KEY_LEVEL = "level"
    }
}
