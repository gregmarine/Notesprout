package com.symmetricalpalmtree.notesproutsn.data.backup

import android.os.Build
import java.util.UUID

/**
 * The name of this device's own folder under `Backups/` in the cloud (arc 25 / V4) — og's D4 shape:
 * a readable model name plus a short random suffix.
 *
 * **Why a random suffix and not the serial.** Two of the same model must not share one folder, so
 * the name needs something per-device in it. A hardware serial is the obvious candidate and is
 * exactly the wrong one (og's rule): it is a durable device identifier, it would sit in the user's
 * cloud in plain sight forever, and the folder needs to be *distinct*, not *identifying*. Eight hex
 * from a random UUID is distinct and says nothing about the device.
 *
 * **It is minted once and then it is the person's.** The default is a starting point — the Backup
 * screen offers Rename… — so nothing downstream may re-derive it; the stored name is the only
 * answer. A *different* name resets the cloud stamp map, because a stamp is a statement about one
 * destination and a folder that has never seen a file cannot claim to hold it.
 *
 * [name] is pure and JVM-tested; [mint] is the one line that reads the device and the clock-free
 * randomness around it.
 */
object DeviceFolder {

    /** What an unnameable model becomes. A folder must be called something. */
    const val FALLBACK = "device"

    /** Hex characters taken from the random UUID for the suffix. */
    const val SUFFIX_CHARS = 8

    /**
     * How much of the model name survives. Long enough for every real model string, short enough
     * that the whole folder name stays far inside the seam's `MAX_NAME_CHARS` (255) even after a
     * pathological `Build.MODEL` — the name has to be legal at the seam or nothing can be uploaded
     * into it at all.
     */
    const val MAX_MODEL_CHARS = 48

    /**
     * The default folder name for [model] with [suffix] appended: everything outside
     * `[a-zA-Z0-9_-]` collapses to a single `-`, leading and trailing `-` go, and a model that
     * leaves nothing behind becomes [FALLBACK].
     *
     * The charset is deliberately narrower than [com.symmetricalpalmtree.notesproutsn.library.NameRules.CHARSET]
     * (no dot, no space): this name is minted by the app rather than typed, it becomes a folder in
     * someone else's file tree, and the narrow form is legal everywhere the wider one is. A name the
     * person types through Rename… is judged by `NameRules` instead — their folder, their spelling.
     */
    fun name(model: String?, suffix: String): String {
        val cleaned = sanitize(model.orEmpty()).take(MAX_MODEL_CHARS).trim('-').ifEmpty { FALLBACK }
        val tail = sanitize(suffix).trim('-')
        return if (tail.isEmpty()) cleaned else "$cleaned-$tail"
    }

    /** A fresh default name for this device. Not pure — this is the only line that reads [Build]. */
    fun mint(): String = name(runCatching { Build.MODEL }.getOrNull(), randomSuffix())

    /** Eight hex characters from a random UUID — distinct, and about the device in no way at all. */
    fun randomSuffix(): String =
        UUID.randomUUID().toString().replace("-", "").take(SUFFIX_CHARS)

    private fun sanitize(raw: String): String {
        val out = StringBuilder(raw.length)
        for (c in raw) {
            val ok = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_' || c == '-'
            if (ok) out.append(c) else if (out.isNotEmpty() && out.last() != '-') out.append('-')
        }
        return out.toString()
    }
}
