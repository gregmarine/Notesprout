package com.notesprout.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks in the storage contract behind the "recognized objects drop their strokes" change: an empty
 * (heading) / null (text) `strokes` field must be **omitted** from the serialized JSON, so a recognized
 * heading or text object writes zero stroke bytes. This depends on `toJson()` using a default `Json`
 * instance (`encodeDefaults = false`) — if that ever changes, these guards fail loudly.
 *
 * No `android.graphics` types are touched (all strokes are empty/null), so this runs as a plain JVM test.
 */
class HeadingTextStrokeOmissionTest {

    @Test
    fun recognizedHeading_omitsEmptyStrokesFromJson() {
        val json = HeadingObject(strokes = emptyList(), recognizedText = "# Chapter One", level = 1).toJson()
        assertFalse("recognized heading JSON must not carry a strokes field: $json", json.contains("\"strokes\""))
        assertTrue(json.contains("\"recognizedText\""))
    }

    @Test
    fun heading_roundTripsMissingStrokesToEmptyList() {
        val decoded = HeadingObject.fromJson("""{"recognizedText":"## Sub","level":2}""")
        assertEquals(emptyList<LiveStroke>(), decoded.strokes)
        assertEquals("## Sub", decoded.recognizedText)
        assertEquals(2, decoded.level)
    }

    @Test
    fun recognizedText_omitsNullStrokesFromJson() {
        val json = TextObject(text = "hello world", strokes = null).toJson()
        assertFalse("recognized text JSON must not carry a strokes field: $json", json.contains("\"strokes\""))
        assertTrue(json.contains("\"text\""))
    }

    @Test
    fun text_roundTripsMissingStrokesToNull() {
        val decoded = TextObject.fromJson("""{"text":"hello world"}""")
        assertNull(decoded.strokes)
        assertEquals("hello world", decoded.text)
    }
}
