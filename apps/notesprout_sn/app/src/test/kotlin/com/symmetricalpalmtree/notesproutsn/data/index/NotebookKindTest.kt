package com.symmetricalpalmtree.notesproutsn.data.index

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [NotebookKind] — the three-way radio read back out of the index bits (arc 43 / K3), and the one
 * place in the app where "a notebook claims to be two kinds at once" is representable and has to be
 * answered.
 */
class NotebookKindTest {

    private val encrypted = NotebookFlags.ENCRYPTED

    @Test
    fun `absent or bare flags are handwritten`() {
        assertEquals(NotebookKind.HANDWRITTEN, NotebookKind.of(null))
        assertEquals(NotebookKind.HANDWRITTEN, NotebookKind.of(0))
        assertEquals(NotebookKind.HANDWRITTEN, NotebookKind.of(encrypted))
    }

    /** The bit never travels alone: every notebook SN writes is encrypted, and some are excluded
     *  from backup. Masking, not equality. */
    @Test
    fun `the other flags never hide a kind`() {
        val noise = encrypted or NotebookFlags.EXCLUDE_FROM_BACKUP
        assertEquals(NotebookKind.TEXT, NotebookKind.of(noise or NotebookFlags.TEXT_DOCUMENT))
        assertEquals(NotebookKind.SKETCH, NotebookKind.of(noise or NotebookFlags.SKETCH))
        assertEquals(NotebookKind.HANDWRITTEN, NotebookKind.of(noise))
    }

    /** No build of this app can write both bits; a row that carries them is foreign or damaged, and
     *  TEXT is the recoverable answer (✓ *Show pages* is one tap away from an empty editor). */
    @Test
    fun `both bits read as TEXT, and the reading site is told`() {
        val both = encrypted or NotebookFlags.TEXT_DOCUMENT or NotebookFlags.SKETCH
        assertEquals(NotebookKind.TEXT, NotebookKind.of(both))
        assertTrue(NotebookKind.conflicting(both))
    }

    @Test
    fun `nothing else is a conflict`() {
        assertFalse(NotebookKind.conflicting(null))
        assertFalse(NotebookKind.conflicting(0))
        assertFalse(NotebookKind.conflicting(encrypted or NotebookFlags.TEXT_DOCUMENT))
        assertFalse(NotebookKind.conflicting(encrypted or NotebookFlags.SKETCH))
    }

    @Test
    fun `flagBits is the inverse of of`() {
        assertEquals(0, NotebookKind.flagBits(NotebookKind.HANDWRITTEN))
        assertEquals(NotebookFlags.TEXT_DOCUMENT, NotebookKind.flagBits(NotebookKind.TEXT))
        assertEquals(NotebookFlags.SKETCH, NotebookKind.flagBits(NotebookKind.SKETCH))
        for (kind in NotebookKind.entries) {
            assertEquals(kind, NotebookKind.of(encrypted or NotebookKind.flagBits(kind)))
        }
    }

    // ── The import's side: the two mirrored booleans ─────────────────────────

    @Test
    fun `fromMeta reads an arriving file's two booleans the same way`() {
        assertEquals(NotebookKind.HANDWRITTEN, NotebookKind.fromMeta(textDocument = false, sketch = false))
        assertEquals(NotebookKind.TEXT, NotebookKind.fromMeta(textDocument = true, sketch = false))
        assertEquals(NotebookKind.SKETCH, NotebookKind.fromMeta(textDocument = false, sketch = true))
        // Untrusted input, same answer as the bits: TEXT wins.
        assertEquals(NotebookKind.TEXT, NotebookKind.fromMeta(textDocument = true, sketch = true))
    }

    @Test
    fun `metaConflicting names the one case fromMeta had to choose in`() {
        assertTrue(NotebookKind.metaConflicting(textDocument = true, sketch = true))
        assertFalse(NotebookKind.metaConflicting(textDocument = true, sketch = false))
        assertFalse(NotebookKind.metaConflicting(textDocument = false, sketch = true))
        assertFalse(NotebookKind.metaConflicting(textDocument = false, sketch = false))
    }

    /** The two readings must agree: a file's meta and the index row written from it are the same
     *  statement, and a drift between them is a notebook that opens differently after a restart. */
    @Test
    fun `fromMeta and of agree on every combination`() {
        for (text in listOf(false, true)) {
            for (sketch in listOf(false, true)) {
                val bits = encrypted or
                    (if (text) NotebookFlags.TEXT_DOCUMENT else 0) or
                    (if (sketch) NotebookFlags.SKETCH else 0)
                assertEquals(NotebookKind.of(bits), NotebookKind.fromMeta(text, sketch))
                assertEquals(NotebookKind.conflicting(bits), NotebookKind.metaConflicting(text, sketch))
            }
        }
    }
}
