package com.symmetricalpalmtree.notesproutsn.ext.document

import com.symmetricalpalmtree.notesproutsn.extension.DocumentContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The push order, against a sink that is a list rather than a Binder. The host's accumulator depends
 * on all of this and can only complain about it by refusing — which the writer would see as a
 * document that silently stopped saving.
 */
class ChunkPushTest {

    private class FakeSink : ChunkSink {
        val calls = mutableListOf<Call>()

        /** Throw on the chunk at this index, the way a revoked binder would. */
        var failAt: Int = -1

        override fun accept(pageKey: String, index: Int, chunk: String, last: Boolean, drafted: Boolean) {
            if (index == failAt) throw IllegalStateException("refused")
            calls += Call(pageKey, index, chunk, last, drafted)
        }

        data class Call(
            val pageKey: String,
            val index: Int,
            val chunk: String,
            val last: Boolean,
            val drafted: Boolean,
        )
    }

    @Test
    fun `a short document is one chunk, index 0, flagged last`() {
        val sink = FakeSink()
        ChunkPush.push(sink, "page-1", "hello")
        assertEquals(1, sink.calls.size)
        assertEquals(FakeSink.Call("page-1", 0, "hello", true, false), sink.calls[0])
    }

    @Test
    fun `empty text is one empty chunk — an empty value is a value`() {
        val sink = FakeSink()
        ChunkPush.push(sink, "page-1", "")
        assertEquals(1, sink.calls.size)
        assertEquals("", sink.calls[0].chunk)
        assertTrue(sink.calls[0].last)
    }

    @Test
    fun `a long document arrives in order from zero with last only on the final chunk`() {
        val sink = FakeSink()
        val text = "x".repeat(DocumentContract.TEXT_CHUNK_CHARS * 2 + 7)
        ChunkPush.push(sink, "page-1", text)

        assertEquals(3, sink.calls.size)
        assertEquals(listOf(0, 1, 2), sink.calls.map { it.index })
        assertEquals(listOf(false, false, true), sink.calls.map { it.last })
        assertEquals(text, sink.calls.joinToString("") { it.chunk })
        assertTrue(sink.calls.all { it.pageKey == "page-1" })
    }

    @Test
    fun `drafted rides every chunk of the save it marks`() {
        val sink = FakeSink()
        ChunkPush.push(sink, "page-1", "x".repeat(DocumentContract.TEXT_CHUNK_CHARS + 1), drafted = true)
        assertEquals(2, sink.calls.size)
        assertTrue(sink.calls.all { it.drafted })
    }

    @Test
    fun `a refusal mid-push surfaces, and nothing after it is sent`() {
        val sink = FakeSink()
        sink.failAt = 1
        try {
            ChunkPush.push(sink, "page-1", "x".repeat(DocumentContract.TEXT_CHUNK_CHARS * 2 + 1))
            fail("the sink's refusal must reach the caller")
        } catch (e: IllegalStateException) {
            // Exactly what a caller needs to keep the buffer dirty.
        }
        assertEquals(listOf(0), sink.calls.map { it.index })
    }

    @Test
    fun `a refused push leaves savedText where it was`() {
        val governor = AutosaveGovernor()
        governor.markLoaded("old")
        val sink = FakeSink().apply { failAt = 0 }
        governor.request("new")
        try {
            ChunkPush.push(sink, "page-1", "new")
            fail("expected the sink to refuse")
        } catch (e: IllegalStateException) {
            governor.onFailed()
        }
        assertEquals("old", governor.savedText)
        assertTrue(governor.isDirty("new"))
    }
}
