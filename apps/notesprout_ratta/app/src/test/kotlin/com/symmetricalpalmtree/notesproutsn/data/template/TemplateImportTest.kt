package com.symmetricalpalmtree.notesproutsn.data.template

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The import rules (arc 13 / G4) — the numbers that decide whether a blob the app can never read
 * back gets written. The Nomad's page is 1404 × 1872, so its long edge is the bound throughout.
 */
class TemplateImportTest {

    private val nomadPageLongEdge = 1872

    // ── sampleSize ───────────────────────────────────────────────────────────

    @Test fun `no sampling when the picture already fits`() {
        assertEquals(1, TemplateImport.sampleSize(1404, 1872, nomadPageLongEdge))
        assertEquals(1, TemplateImport.sampleSize(800, 600, nomadPageLongEdge))
    }

    @Test fun `sampling leaves the long edge at or above the bound`() {
        // The contract that matters: after sampling there is still something to scale DOWN from.
        for (long in listOf(1873, 2000, 3744, 3745, 8000, 12_000, 40_000)) {
            val sample = TemplateImport.sampleSize(long, long / 2, nomadPageLongEdge)
            val decoded = long / sample
            assertTrue("long=$long sample=$sample decoded=$decoded", decoded >= nomadPageLongEdge)
        }
    }

    @Test fun `sampling is the largest power of two that still clears the bound`() {
        // 3744 = 2 x 1872 exactly: halving lands ON the bound, which still clears it.
        assertEquals(2, TemplateImport.sampleSize(3744, 2808, nomadPageLongEdge))
        // 3743 halves to 1871, one short — so it must not halve at all.
        assertEquals(1, TemplateImport.sampleSize(3743, 2807, nomadPageLongEdge))
        assertEquals(4, TemplateImport.sampleSize(7488, 5616, nomadPageLongEdge))
    }

    @Test fun `sampling never returns less than one`() {
        assertEquals(1, TemplateImport.sampleSize(0, 0, nomadPageLongEdge))
        assertEquals(1, TemplateImport.sampleSize(-4, 10, nomadPageLongEdge))
        assertEquals(1, TemplateImport.sampleSize(4000, 3000, 0))
    }

    @Test fun `the short edge does not drive the sample`() {
        // A tall strip: the long edge is what has to clear the bound, not both.
        assertEquals(2, TemplateImport.sampleSize(200, 4000, nomadPageLongEdge))
    }

    // ── scaledSize ───────────────────────────────────────────────────────────

    @Test fun `a picture at or under the bound is left exactly alone`() {
        assertNull(TemplateImport.scaledSize(1404, 1872, nomadPageLongEdge))
        assertNull(TemplateImport.scaledSize(1872, 1404, nomadPageLongEdge))
        assertNull(TemplateImport.scaledSize(400, 300, nomadPageLongEdge))
    }

    @Test fun `never upscales`() {
        // The whole reason null exists: a small sketch stays small rather than being blown up here.
        assertNull(TemplateImport.scaledSize(100, 100, nomadPageLongEdge))
    }

    @Test fun `the long edge lands exactly on the bound, either orientation`() {
        val portrait = TemplateImport.scaledSize(3000, 4000, nomadPageLongEdge)!!
        assertEquals(nomadPageLongEdge, portrait.second)
        assertEquals(1404, portrait.first)

        val landscape = TemplateImport.scaledSize(4000, 3000, nomadPageLongEdge)!!
        assertEquals(nomadPageLongEdge, landscape.first)
        assertEquals(1404, landscape.second)
    }

    @Test fun `aspect is kept within a pixel`() {
        val (w, h) = TemplateImport.scaledSize(5000, 3333, nomadPageLongEdge)!!
        val before = 5000.0 / 3333.0
        val after = w.toDouble() / h
        assertTrue("$before vs $after", Math.abs(before - after) < 0.002)
    }

    @Test fun `a degenerate strip keeps at least one pixel on its short edge`() {
        // 3000 x 2 scaled to a 1872 long edge rounds the short edge to 1, not 0 —
        // createScaledBitmap throws on zero.
        val (w, h) = TemplateImport.scaledSize(3000, 2, nomadPageLongEdge)!!
        assertEquals(nomadPageLongEdge, w)
        assertTrue(h >= 1)
    }

    @Test fun `a degenerate input is not scaled`() {
        assertNull(TemplateImport.scaledSize(0, 100, nomadPageLongEdge))
        assertNull(TemplateImport.scaledSize(100, 100, 0))
    }

    // ── The cap ──────────────────────────────────────────────────────────────

    @Test fun `the cap is six mebibytes and sits under the read ceiling`() {
        assertEquals(6 * 1024 * 1024, TemplateImport.MAX_BLOB_BYTES)
        // The B3 trap: SQLCipher's CursorWindow is 8 MiB, and a blob over it writes fine and then
        // cannot be read. The cap must leave room for the row's other columns.
        assertTrue(TemplateImport.MAX_BLOB_BYTES < 8 * 1024 * 1024)
    }

    @Test fun `the cap is a ceiling, not a threshold`() {
        assertFalse(TemplateImport.overCap(TemplateImport.MAX_BLOB_BYTES))
        assertTrue(TemplateImport.overCap(TemplateImport.MAX_BLOB_BYTES + 1))
        assertFalse(TemplateImport.overCap(0))
    }

    @Test fun `sizes are said in the units a file manager uses`() {
        assertEquals("6.3 MB", TemplateImport.megabytes(TemplateImport.MAX_BLOB_BYTES))
        assertEquals("1.5 MB", TemplateImport.megabytes(1_450_000))
        assertEquals("0.0 MB", TemplateImport.megabytes(0))
    }

    // ── nameFrom ─────────────────────────────────────────────────────────────

    @Test fun `the extension is dropped and the basename kept`() {
        assertEquals("scan-01", TemplateImport.nameFrom("scan-01.png", "fallback"))
        assertEquals("A Grid Paper", TemplateImport.nameFrom("A Grid Paper.jpeg", "fallback"))
    }

    @Test fun `a path keeps only its last segment`() {
        assertEquals("ruled", TemplateImport.nameFrom("/storage/emulated/0/Download/ruled.webp", "fallback"))
    }

    @Test fun `refused characters become spaces rather than vanishing`() {
        // IMG_0031(2) must not read as IMG_00312 — a different file's name.
        assertEquals("IMG_0031 2", TemplateImport.nameFrom("IMG_0031(2).png", "fallback"))
        assertEquals("a b", TemplateImport.nameFrom("a@#\$b.png", "fallback"))
    }

    @Test fun `the result always passes the family charset`() {
        val charset = Regex("^[a-zA-Z0-9_\\-. ]*$")
        for (raw in listOf("héllo wörld.png", "画像.jpeg", "a/b\\c:d.png", "tab\there.png")) {
            val name = TemplateImport.nameFrom(raw, "fallback")
            assertTrue("$raw → $name", charset.matches(name))
        }
    }

    @Test fun `an unusable name falls back`() {
        assertEquals("fallback", TemplateImport.nameFrom(null, "fallback"))
        assertEquals("fallback", TemplateImport.nameFrom("", "fallback"))
        assertEquals("fallback", TemplateImport.nameFrom(".hidden", "fallback"))
        assertEquals("fallback", TemplateImport.nameFrom("###.png", "fallback"))
        // "." and ".." are reserved by NameRules, so offering them would be offering a refusal.
        assertEquals("fallback", TemplateImport.nameFrom("..png", "fallback"))
    }

    @Test fun `only the three locked formats are offered to the picker`() {
        assertEquals(listOf("image/png", "image/jpeg", "image/webp"), TemplateImport.MIME_TYPES.toList())
    }
}
