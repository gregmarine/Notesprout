package com.symmetricalpalmtree.notesprout.extension

/**
 * The host's caps around `IObjectProvider.describeOutline` (arc 5 / C0 — pure Kotlin, JVM-tested):
 * **outward** the payloads are chunked so one Binder call never exceeds `MAX_OUTLINE_BATCH` items or
 * `MAX_OUTLINE_BATCH_CHARS` summed chars; **inward** a reply is trusted only if it has *exactly* the
 * input's length (any other length — including the malformed empty reply an old provider produces
 * for an unknown transaction — means "this provider does not answer the outline" for this call), then
 * every entry is normalised: label trimmed and cut to `MAX_OUTLINE_LABEL_CHARS`, a blank label with
 * level ≥ 1 → level 0, a level outside `0..MAX_OUTLINE_LEVEL` → 0. Nothing else is trusted.
 */
object OutlineCaps {

    /**
     * Greedy chunking: a chunk closes at `MAX_OUTLINE_BATCH` items or when adding the next payload
     * would pass `MAX_OUTLINE_BATCH_CHARS`. A single payload longer than the char cap (impossible after
     * `MAX_OBJECT_TEXT_CHARS`, but checked) becomes its own chunk, truncated to the cap on the way out.
     * Chunks concatenate back to the input order; an empty input → no chunks.
     */
    fun chunk(payloads: List<String>): List<List<String>> {
        val out = ArrayList<List<String>>()
        var cur = ArrayList<String>()
        var chars = 0
        for (raw in payloads) {
            val p = if (raw.length > ExtensionContract.MAX_OUTLINE_BATCH_CHARS) raw.substring(0, ExtensionContract.MAX_OUTLINE_BATCH_CHARS) else raw
            if (cur.isNotEmpty() && (cur.size >= ExtensionContract.MAX_OUTLINE_BATCH || chars + p.length > ExtensionContract.MAX_OUTLINE_BATCH_CHARS)) {
                out += cur; cur = ArrayList(); chars = 0
            }
            cur += p; chars += p.length
        }
        if (cur.isNotEmpty()) out += cur
        return out
    }

    /** One entry as the host keeps it: label trimmed + cut, level clamped, blank → level 0. */
    data class Entry(val label: String, val level: Int)

    /**
     * Validate a reply of [expected] entries: null / wrong length → **null** (the provider does not
     * answer this call); else one normalised [Entry] per input, in order.
     */
    fun sanitize(reply: List<OutlineEntry?>?, expected: Int): List<Entry>? {
        if (reply == null || reply.size != expected) return null
        return reply.map { e ->
            if (e == null) return@map Entry("", 0)
            val label = e.label.trim().let { if (it.length > ExtensionContract.MAX_OUTLINE_LABEL_CHARS) it.substring(0, ExtensionContract.MAX_OUTLINE_LABEL_CHARS) else it }
            val level = if (e.level in 1..ExtensionContract.MAX_OUTLINE_LEVEL && label.isNotBlank()) e.level else 0
            Entry(if (level == 0) "" else label, level)
        }
    }

    /** The load probe's test: a reply of exactly [expected] (1) entries means "outline-capable". */
    fun isCapableReply(reply: List<OutlineEntry?>?, expected: Int = 1): Boolean = reply != null && reply.size == expected
}
