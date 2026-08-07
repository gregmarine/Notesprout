package com.notesprout.android.core.markdown

import android.text.Editable

/**
 * The slice of editing behaviour [MarkdownFormatter] needs from a text buffer.
 *
 * Keeping the formatter free of `android.text` is what lets its operations — where every caret
 * off-by-one lives — be unit-tested on the JVM, with no Robolectric and no device.
 */
interface TextBuffer {
    val length: Int
    operator fun get(index: Int): Char
    fun substring(start: Int, end: Int): String
    fun replace(start: Int, end: Int, replacement: String)
}

fun TextBuffer.insert(at: Int, text: String) = replace(at, at, text)

fun TextBuffer.delete(start: Int, end: Int) = replace(start, end, "")

/** [TextBuffer] over the live [Editable] of an EditText. */
class EditableBuffer(private val editable: Editable) : TextBuffer {
    override val length: Int get() = editable.length
    override fun get(index: Int): Char = editable[index]
    override fun substring(start: Int, end: Int): String = editable.subSequence(start, end).toString()
    override fun replace(start: Int, end: Int, replacement: String) {
        editable.replace(start, end, replacement)
    }
}
