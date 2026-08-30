package com.symmetricalpalmtree.notesproutsn.markdown

import android.text.Editable

/**
 * The narrow view of a mutable text store that [MarkdownFormatter] edits through.
 *
 * The formatter is where every caret off-by-one in the document editor lives, so it must be
 * testable without a device. Four members are all it needs, and none of them mention
 * `android.text` — which is what lets the whole of [MarkdownFormatter] run under plain JUnit
 * with no Robolectric and no instrumentation.
 *
 * Indices are character offsets, half-open (`start` inclusive, `end` exclusive), exactly as an
 * `Editable` treats them.
 */
interface TextBuffer {
    val length: Int
    operator fun get(index: Int): Char
    fun substring(start: Int, end: Int): String
    fun replace(start: Int, end: Int, replacement: String)
}

/** Splice [text] in at [at] without removing anything. */
fun TextBuffer.insert(at: Int, text: String) = replace(at, at, text)

/** Remove `[start, end)`. */
fun TextBuffer.delete(start: Int, end: Int) = replace(start, end, "")

/**
 * The one adapter that binds the formatter to a real EditText: a [TextBuffer] writing straight
 * through to the live [Editable].
 *
 * This class is the sole reason this file imports `android.text` — everything else in `:markdown`
 * that the formatter touches is pure Kotlin, and it must stay that way.
 */
class EditableBuffer(private val editable: Editable) : TextBuffer {
    override val length: Int get() = editable.length
    override fun get(index: Int): Char = editable[index]
    override fun substring(start: Int, end: Int): String = editable.subSequence(start, end).toString()
    override fun replace(start: Int, end: Int, replacement: String) {
        editable.replace(start, end, replacement)
    }
}
