package com.notesprout.android.data

import com.notesprout.android.recognition.PageTextRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PageTextSchemaTest {

    @Test
    fun schema1JsonStillDecodes() {
        val v1 = """{"text":"hello","engine":"mlkit","recognizedAt":1,"sourceMaxUpdatedAt":2,"schema":1}"""
        val pt = PageText.fromJson(v1)
        assertEquals("hello", pt.text)
        assertEquals(1, pt.schema)
        assertNull(pt.lines) // no correction offered for pre-lines rows
    }

    @Test
    fun schema2RoundTripsLines() {
        val pt = PageText(
            text = "a\nb",
            engine = PageText.ENGINE_TROCR,
            recognizedAt = 1,
            sourceMaxUpdatedAt = 2,
            lines = listOf(
                PageText.RecognizedLine("a", listOf("s1", "s2"), top = 10f, height = 20f),
                PageText.RecognizedLine("b", listOf("s3"), top = 40f, height = 18f),
            ),
        )
        val back = PageText.fromJson(pt.toJson())
        assertEquals(PageText.CURRENT_SCHEMA, back.schema)
        assertEquals(2, back.lines!!.size)
        assertEquals(listOf("s1", "s2"), back.lines!![0].strokeIds)
        assertEquals("b", back.lines!![1].text)
    }

    @Test
    fun cacheFromAnOlderPipelineIsStaleEvenWhenTheWatermarkSays_otherwise() {
        // The watermark only notices the page changing. A page that has sat still since the recognizer
        // learned to read inside links would otherwise keep serving text that is missing them.
        val old = PageText.fromJson(
            """{"text":"hello","engine":"mlkit","recognizedAt":1,"sourceMaxUpdatedAt":50,"schema":2}"""
        )
        assertFalse(PageTextRepository.isFresh(old, currentMax = 10))
        assertTrue(PageTextRepository.isFresh(old.copy(schema = PageText.CURRENT_SCHEMA), currentMax = 10))
    }

    @Test
    fun aStalePageIsStaleWhateverTheSchema() {
        val current = PageText(
            text = "hello", engine = PageText.ENGINE_MLKIT, recognizedAt = 1, sourceMaxUpdatedAt = 50,
        )
        assertFalse(PageTextRepository.isFresh(current, currentMax = 51))
        assertTrue(PageTextRepository.isFresh(current, currentMax = 50))
    }
}
