package com.symmetricalpalmtree.notesproutsn.ext.document

/**
 * Where the writer left off, as data — the caret LRU with no Android in it (arc 19 / M5).
 *
 * The map is kept here rather than in the `.soil` on purpose: where a caret sits is this device's
 * view state, not part of the document, and it would otherwise need a column in a format written to
 * be handed to other projects. The cost is that it does not travel with an exported notebook, which
 * is the right trade for something the next keystroke overwrites anyway.
 *
 * **The encoding is a persistence format**, so it is spelled out rather than derived: one UTF-8
 * blob, one `<key>\t<offset>\n` line per page, oldest first. A line codec rather than JSON because
 * this module carries no serialization dependency and never will — the store is two hundred short
 * lines at its largest, and a hand-written codec is the honest size for it.
 *
 * **Nothing here can be the reason a screen fails to open.** Every decode path degrades to a
 * map — a malformed line is skipped, a bad offset is clamped, and bytes that are not this format at
 * all come back empty. Losing a caret costs one scroll; refusing to open costs the document.
 */
object CaretMemory {

    /**
     * How many pages' carets to keep. Old entries fall off the front, so the store cannot grow
     * without bound in a notebook of a thousand pages.
     */
    const val LIMIT = 100

    /** The stored map, oldest entry first. `null`, empty or unreadable bytes → an empty map. */
    fun decode(bytes: ByteArray?): LinkedHashMap<String, Int> {
        val out = LinkedHashMap<String, Int>()
        if (bytes == null || bytes.isEmpty()) return out
        return try {
            for (line in bytes.toString(Charsets.UTF_8).split('\n')) {
                if (line.isEmpty()) continue
                val tab = line.indexOf('\t')
                // A key of nothing, or a line with no offset after the tab, is not a record.
                if (tab <= 0 || tab == line.length - 1) continue
                val offset = line.substring(tab + 1).toIntOrNull() ?: continue
                val key = line.substring(0, tab)
                // Remove-then-put so a duplicated key keeps its LAST position, matching [record].
                out.remove(key)
                out[key] = offset.coerceAtLeast(0)
            }
            out
        } catch (e: Exception) {
            LinkedHashMap()
        }
    }

    /**
     * [map] as the stored blob, in iteration order.
     *
     * A key carrying a tab or a newline is dropped rather than escaped: host page keys are
     * UUID-shaped and cannot contain either, so this is defense against a future caller, and a
     * dropped caret is a scroll while a corrupted line would take the whole map with it.
     */
    fun encode(map: Map<String, Int>): ByteArray {
        val sb = StringBuilder()
        for ((key, offset) in map) {
            if (key.isEmpty() || key.contains('\t') || key.contains('\n')) continue
            sb.append(key).append('\t').append(offset.coerceAtLeast(0)).append('\n')
        }
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    /**
     * Remember [offset] for [key], evicting the oldest entry once past [LIMIT]. Mutates and returns
     * [map].
     *
     * Removed before it is put back, so a re-saved key moves to the back — eviction is then
     * least-recently-*written*, which is what makes the page being typed in the last one to go.
     */
    fun record(map: LinkedHashMap<String, Int>, key: String, offset: Int): LinkedHashMap<String, Int> {
        if (key.isEmpty()) return map
        map.remove(key)
        map[key] = offset.coerceAtLeast(0)
        while (map.size > LIMIT) {
            val oldest = map.keys.firstOrNull() ?: break
            map.remove(oldest)
        }
        return map
    }
}
