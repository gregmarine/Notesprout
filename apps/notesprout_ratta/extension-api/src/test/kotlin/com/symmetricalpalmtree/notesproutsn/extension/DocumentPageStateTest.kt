package com.symmetricalpalmtree.notesproutsn.extension

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/** The state parcelable's constructor `require`s — unmarshal is the validation (family rule). */
class DocumentPageStateTest {

    private fun state(
        pageKey: String = "page-1",
        scope: Int = DocumentContract.SCOPE_PAGE,
        pageIndex: Int = 0,
        pageCount: Int = 3,
        title: String = "Notebook",
        textDocument: Boolean = false,
        source: Int = DocumentContract.SOURCE_NONE,
        textChars: Int = 0,
        textChunks: Int = 1,
    ) = DocumentPageState(pageKey, scope, pageIndex, pageCount, title, textDocument, source, textChars, textChunks)

    private fun assertRefused(build: () -> DocumentPageState) {
        try {
            build()
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
        }
    }

    @Test
    fun aPlainStateHolds() {
        val s = state(textChars = 12, textChunks = 1)
        assertEquals("page-1", s.pageKey)
        assertEquals(1, s.textChunks)
    }

    @Test
    fun notebookScopeUsesTheNotAPageIndex() {
        state(scope = DocumentContract.SCOPE_NOTEBOOK, pageIndex = -1)
        assertRefused { state(scope = DocumentContract.SCOPE_NOTEBOOK, pageIndex = 0) }
    }

    @Test
    fun pageIndexMustSitInsideTheCount() {
        assertRefused { state(pageIndex = 3, pageCount = 3) }
        assertRefused { state(pageIndex = -1, pageCount = 3) }
        assertRefused { state(pageCount = 0, pageIndex = -1, scope = DocumentContract.SCOPE_NOTEBOOK) }
    }

    @Test
    fun pageKeyIsBoundedAndPathFree() {
        assertRefused { state(pageKey = "") }
        assertRefused { state(pageKey = "k".repeat(DocumentContract.MAX_PAGE_KEY_CHARS + 1)) }
        assertRefused { state(pageKey = "a/b") }
        state(pageKey = "k".repeat(DocumentContract.MAX_PAGE_KEY_CHARS))
    }

    @Test
    fun titleIsBounded() {
        state(title = "")
        state(title = "t".repeat(DocumentContract.MAX_TITLE_CHARS))
        assertRefused { state(title = "t".repeat(DocumentContract.MAX_TITLE_CHARS + 1)) }
    }

    @Test
    fun unknownEnumsAreRefused() {
        assertRefused { state(scope = 2) }
        assertRefused { state(source = 3) }
    }

    @Test
    fun textShapeIsCoherent() {
        state(textChars = DocumentContract.MAX_DOCUMENT_CHARS, textChunks = DocumentContract.TEXT_MAX_CHUNKS)
        assertRefused { state(textChars = DocumentContract.MAX_DOCUMENT_CHARS + 1) }
        assertRefused { state(textChunks = 0) }
        assertRefused { state(textChunks = DocumentContract.TEXT_MAX_CHUNKS + 1) }
        // Empty text is exactly one (empty) chunk — the TextChunks rule.
        assertRefused { state(textChars = 0, textChunks = 2) }
    }
}
