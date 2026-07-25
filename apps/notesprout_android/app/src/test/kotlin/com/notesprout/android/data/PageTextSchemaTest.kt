package com.notesprout.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        assertEquals(2, back.schema)
        assertEquals(2, back.lines!!.size)
        assertEquals(listOf("s1", "s2"), back.lines!![0].strokeIds)
        assertEquals("b", back.lines!![1].text)
    }
}
