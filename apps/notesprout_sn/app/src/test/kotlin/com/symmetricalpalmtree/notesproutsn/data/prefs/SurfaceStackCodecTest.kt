package com.symmetricalpalmtree.notesproutsn.data.prefs

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The surface-stack algebra (arc 32 / RS1) — [SurfaceStackCodec.decode] treating stored prefs as
 * untrusted input (a corrupt blob or an unknown surface name never crashes and never takes the
 * whole stack down with it), and the three mutations ([SurfaceStackCodec.attach],
 * [SurfaceStackCodec.markTop], [SurfaceStackCodec.pop]) each touching only the entry they name.
 */
class SurfaceStackCodecTest {

    @Test
    fun `a mixed stack of all five surfaces round-trips through encode and decode`() {
        val entries = listOf(
            SurfaceEntry("t1", Surface.NOTEBOOK, notebookId = "nb-1", viaLink = true),
            SurfaceEntry("t2", Surface.CALENDAR),
            SurfaceEntry("t3", Surface.SCRATCH_PAD),
            SurfaceEntry("t4", Surface.DOCUMENT_EDITOR),
            SurfaceEntry("t5", Surface.BIBLE),
        )
        assertEquals(entries, SurfaceStackCodec.decode(SurfaceStackCodec.encode(entries)))
    }

    @Test
    fun `an empty stack round-trips`() {
        assertEquals(emptyList<SurfaceEntry>(), SurfaceStackCodec.decode(SurfaceStackCodec.encode(emptyList())))
    }

    @Test
    fun `null blank garbage and wrong-shaped JSON all read as empty`() {
        assertEquals(emptyList<SurfaceEntry>(), SurfaceStackCodec.decode(null))
        assertEquals(emptyList<SurfaceEntry>(), SurfaceStackCodec.decode(""))
        assertEquals(emptyList<SurfaceEntry>(), SurfaceStackCodec.decode("   "))
        assertEquals(emptyList<SurfaceEntry>(), SurfaceStackCodec.decode("]]not json[["))
        assertEquals(emptyList<SurfaceEntry>(), SurfaceStackCodec.decode("""{"token":"a","surface":"NOTEBOOK"}"""))
        assertEquals(emptyList<SurfaceEntry>(), SurfaceStackCodec.decode("""[{"foo":1}]"""))
    }

    @Test
    fun `an entry with an unknown surface name is dropped while its neighbours survive`() {
        val raw = """
            [
              {"token":"t1","surface":"NOTEBOOK","notebookId":"nb-1","viaLink":false},
              {"token":"t2","surface":"WIDGET_PICKER"},
              {"token":"t3","surface":"CALENDAR"}
            ]
        """.trimIndent()
        val decoded = SurfaceStackCodec.decode(raw)
        assertEquals(
            listOf(
                SurfaceEntry("t1", Surface.NOTEBOOK, notebookId = "nb-1"),
                SurfaceEntry("t3", Surface.CALENDAR),
            ),
            decoded,
        )
    }

    @Test
    fun `a blank token is dropped`() {
        val raw = """
            [
              {"token":"t1","surface":"CALENDAR"},
              {"token":"","surface":"SCRATCH_PAD"}
            ]
        """.trimIndent()
        assertEquals(listOf(SurfaceEntry("t1", Surface.CALENDAR)), SurfaceStackCodec.decode(raw))
    }

    @Test
    fun `attach appends a new token`() {
        val entries = listOf(SurfaceEntry("t1", Surface.NOTEBOOK, notebookId = "nb-1"))
        val out = SurfaceStackCodec.attach(entries, SurfaceEntry("t2", Surface.CALENDAR))
        assertEquals(
            listOf(
                SurfaceEntry("t1", Surface.NOTEBOOK, notebookId = "nb-1"),
                SurfaceEntry("t2", Surface.CALENDAR),
            ),
            out,
        )
    }

    @Test
    fun `attach with an existing token replaces in place at the same index`() {
        val entries = listOf(
            SurfaceEntry("t1", Surface.NOTEBOOK, notebookId = "nb-1", viaLink = false),
            SurfaceEntry("t2", Surface.CALENDAR),
        )
        val out = SurfaceStackCodec.attach(entries, SurfaceEntry("t1", Surface.NOTEBOOK, notebookId = "nb-2", viaLink = true))
        assertEquals(
            listOf(
                SurfaceEntry("t1", Surface.NOTEBOOK, notebookId = "nb-2", viaLink = true),
                SurfaceEntry("t2", Surface.CALENDAR),
            ),
            out,
        )
    }

    @Test
    fun `markTop drops everything above the token and keeps the token itself`() {
        val entries = listOf(
            SurfaceEntry("t1", Surface.NOTEBOOK, notebookId = "nb-1"),
            SurfaceEntry("t2", Surface.CALENDAR),
            SurfaceEntry("t3", Surface.SCRATCH_PAD),
        )
        val out = SurfaceStackCodec.markTop(entries, "t2")
        assertEquals(
            listOf(
                SurfaceEntry("t1", Surface.NOTEBOOK, notebookId = "nb-1"),
                SurfaceEntry("t2", Surface.CALENDAR),
            ),
            out,
        )
    }

    @Test
    fun `markTop on the top entry is a no-op`() {
        val entries = listOf(
            SurfaceEntry("t1", Surface.NOTEBOOK, notebookId = "nb-1"),
            SurfaceEntry("t2", Surface.CALENDAR),
        )
        assertEquals(entries, SurfaceStackCodec.markTop(entries, "t2"))
    }

    @Test
    fun `markTop with an unknown token leaves the list unchanged`() {
        val entries = listOf(
            SurfaceEntry("t1", Surface.NOTEBOOK, notebookId = "nb-1"),
            SurfaceEntry("t2", Surface.CALENDAR),
        )
        assertEquals(entries, SurfaceStackCodec.markTop(entries, "ghost"))
    }

    @Test
    fun `pop removes only that token wherever it is, middle included`() {
        val entries = listOf(
            SurfaceEntry("t1", Surface.NOTEBOOK, notebookId = "nb-1"),
            SurfaceEntry("t2", Surface.CALENDAR),
            SurfaceEntry("t3", Surface.SCRATCH_PAD),
        )
        val out = SurfaceStackCodec.pop(entries, "t2")
        assertEquals(
            listOf(
                SurfaceEntry("t1", Surface.NOTEBOOK, notebookId = "nb-1"),
                SurfaceEntry("t3", Surface.SCRATCH_PAD),
            ),
            out,
        )
    }

    @Test
    fun `pop of an unknown token is a no-op`() {
        val entries = listOf(SurfaceEntry("t1", Surface.NOTEBOOK, notebookId = "nb-1"))
        assertEquals(entries, SurfaceStackCodec.pop(entries, "ghost"))
    }

    @Test
    fun `migrate a present stack blob wins over the legacy keys even when it decodes empty`() {
        val out = SurfaceStackCodec.migrate(stackRaw = "[]", legacyNotebookId = "nb-legacy", legacyViaLink = true)
        assertEquals(emptyList<SurfaceEntry>(), out)
    }

    @Test
    fun `migrate no stack plus a legacy id yields one NOTEBOOK entry with that id and the viaLink flag`() {
        val out = SurfaceStackCodec.migrate(stackRaw = null, legacyNotebookId = "nb-legacy", legacyViaLink = true)
        assertEquals(listOf(SurfaceEntry("legacy", Surface.NOTEBOOK, notebookId = "nb-legacy", viaLink = true)), out)
    }

    @Test
    fun `migrate no stack and no legacy is empty`() {
        assertEquals(
            emptyList<SurfaceEntry>(),
            SurfaceStackCodec.migrate(stackRaw = null, legacyNotebookId = null, legacyViaLink = false),
        )
    }
}
