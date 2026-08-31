package com.symmetricalpalmtree.notesproutsn.importing

import com.symmetricalpalmtree.notesproutsn.extension.DocumentContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TextImportTest {

    private fun decodeProblem(bytes: ByteArray): TextImport.Refusal =
        assertThrows(TextImport.TextProblem::class.java) { TextImport.decode(bytes) }.refusal

    @Test
    fun plainTextPassesThroughVerbatim() {
        assertEquals("# Title\n\nBody.", TextImport.decode("# Title\n\nBody.".toByteArray()))
    }

    @Test
    fun multiByteUtf8Survives() {
        val text = "café — 記録 🌱"
        assertEquals(text, TextImport.decode(text.toByteArray()))
    }

    @Test
    fun lineEndingsNormalizeToLf() {
        assertEquals("a\nb\nc\n", TextImport.decode("a\r\nb\rc\r\n".toByteArray()))
    }

    @Test
    fun leadingBomIsDropped() {
        assertEquals("hello", TextImport.decode("\uFEFFhello".toByteArray()))
        // Only a LEADING BOM is a byte-order mark; anywhere else U+FEFF is content.
        assertEquals("a\uFEFFb", TextImport.decode("a\uFEFFb".toByteArray()))
    }

    @Test
    fun emptyFileIsEmptyText() {
        assertEquals("", TextImport.decode(ByteArray(0)))
    }

    @Test
    fun malformedUtf8IsRefusedNotReplaced() {
        // A lone continuation byte and a truncated 3-byte sequence — both must REPORT, never
        // land U+FFFD in a document.
        assertEquals(TextImport.Refusal.NOT_TEXT, decodeProblem(byteArrayOf(0x68, -0x80)))
        assertEquals(TextImport.Refusal.NOT_TEXT, decodeProblem(byteArrayOf(-0x1E, -0x7C)))
    }

    @Test
    fun nulBytesAreBinaryWearingATextExtension() {
        assertEquals(TextImport.Refusal.NOT_TEXT, decodeProblem(byteArrayOf(0x68, 0x00, 0x69)))
    }

    @Test
    fun overTheByteCapIsTooLong() {
        val big = ByteArray(TextImport.MAX_TEXT_BYTES.toInt() + 1) { 'a'.code.toByte() }
        assertEquals(TextImport.Refusal.TOO_LONG, decodeProblem(big))
    }

    @Test
    fun exactlyTheByteCapIsAccepted() {
        // 10 MB of ASCII is exactly MAX_DOCUMENT_CHARS chars — the caps are aligned on purpose.
        val exact = ByteArray(TextImport.MAX_TEXT_BYTES.toInt()) { 'a'.code.toByte() }
        assertEquals(DocumentContract.MAX_DOCUMENT_CHARS, TextImport.decode(exact).length)
    }

    @Test
    fun normalizeNeverGrows() {
        val text = "x\r\ny\rz\uFEFF"
        assert(TextImport.normalize(text).length <= text.length)
    }
}
