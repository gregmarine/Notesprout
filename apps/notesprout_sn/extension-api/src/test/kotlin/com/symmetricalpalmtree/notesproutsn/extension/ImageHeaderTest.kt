package com.symmetricalpalmtree.notesproutsn.extension

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The WebP header guard (arc 45 / G2, `PngHeader`'s successor), proved on hand-built bytes — which
 * is the whole reason it reads the header by hand instead of asking `BitmapFactory`: every boundary
 * here is a laptop test. Both lossless-capable first chunks are built, `VP8L` and `VP8X`.
 */
class ImageHeaderTest {

    /** `RIFF` · size · `WEBP` · a `VP8L` chunk (signature, packed 14-bit dims) + [tail] bytes. */
    private fun vp8l(
        width: Int,
        height: Int,
        signature: Int = 0x2F,
        version: Int = 0,
        alpha: Boolean = true,
        declaredLength: Int = 5,
        tag: String = "VP8L",
        tail: Int = 0,
    ): ByteArray {
        val packed = ((width - 1) and 0x3FFF) or
            (((height - 1) and 0x3FFF) shl 14) or
            ((if (alpha) 1 else 0) shl 28) or
            ((version and 0x7) shl 29)
        val payload = listOf(signature.toByte()) + le32(packed)
        return riff(tag, declaredLength, payload, tail)
    }

    /** `RIFF` · size · `WEBP` · a `VP8X` chunk (flags, reserved, 24-bit dims) + [tail] bytes. */
    private fun vp8x(
        width: Int,
        height: Int,
        declaredLength: Int = 10,
        tag: String = "VP8X",
        tail: Int = 0,
    ): ByteArray {
        val payload = listOf(0x10.toByte(), 0, 0, 0) + le24(width - 1) + le24(height - 1)
        return riff(tag, declaredLength, payload, tail)
    }

    private fun riff(tag: String, declaredLength: Int, payload: List<Byte>, tail: Int): ByteArray {
        val out = ArrayList<Byte>()
        out += "RIFF".map { it.code.toByte() }
        out += le32(4 + 8 + payload.size + tail)   // the RIFF size — read by nobody here
        out += "WEBP".map { it.code.toByte() }
        out += tag.map { it.code.toByte() }
        out += le32(declaredLength)
        out += payload
        repeat(tail) { out += 0x7F.toByte() }
        return out.toByteArray()
    }

    private fun le32(v: Int): List<Byte> =
        listOf(v.toByte(), (v ushr 8).toByte(), (v ushr 16).toByte(), (v ushr 24).toByte())

    private fun le24(v: Int): List<Byte> =
        listOf(v.toByte(), (v ushr 8).toByte(), (v ushr 16).toByte())

    @Test
    fun aSimpleLosslessHeaderParses() {
        val h = ImageHeader.parse(vp8l(1404, 1685))!!
        assertEquals(1404, h.width)
        assertEquals(1685, h.height)
        assertEquals(ImageHeader.CHUNK_VP8L, h.chunk)
    }

    @Test
    fun anExtendedHeaderParses() {
        val h = ImageHeader.parse(vp8x(1860, 2480))!!
        assertEquals(1860, h.width)
        assertEquals(2480, h.height)
        assertEquals(ImageHeader.CHUNK_VP8X, h.chunk)
    }

    @Test
    fun sizeDerivesFromParse() {
        assertEquals(1404 to 1685, ImageHeader.size(vp8l(1404, 1685)))
        assertEquals(1404 to 1685, ImageHeader.size(vp8x(1404, 1685)))
        assertNull(ImageHeader.size(ByteArray(0)))
    }

    @Test
    fun anEmptyArrayIsNull() {
        assertNull(ImageHeader.parse(ByteArray(0)))
    }

    @Test
    fun aPngIsNotAWebp() {
        // Decision 4: no legacy. A PNG row from an arc-43/44 build is simply not an image this seam
        // reads — refused, never sniffed for.
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(40)
        assertNull(ImageHeader.parse(png))
        assertFalse(ImageHeader.matches(png, 1, 1))
    }

    @Test
    fun aWrongPreambleIsNull() {
        val bad = vp8l(10, 10)
        bad[0] = 'R'.code.toByte(); bad[1] = 'I'.code.toByte(); bad[2] = 'F'.code.toByte(); bad[3] = 'X'.code.toByte()
        assertNull(ImageHeader.parse(bad))
        val alsoBad = vp8l(10, 10)
        alsoBad[8] = 'W'.code.toByte(); alsoBad[9] = 'E'.code.toByte(); alsoBad[10] = 'B'.code.toByte(); alsoBad[11] = 'M'.code.toByte()
        assertNull(ImageHeader.parse(alsoBad))
    }

    @Test
    fun aFirstChunkThatIsNeitherFormIsNull() {
        // The lossy simple form — nothing on this seam writes one, and its header is a different
        // shape entirely, so it is refused rather than guessed at.
        assertNull(ImageHeader.parse(vp8l(10, 10, tag = "VP8 ")))
        assertNull(ImageHeader.parse(vp8l(10, 10, tag = "vp8l")))
        assertNull(ImageHeader.parse(vp8x(10, 10, tag = "ALPH")))
    }

    @Test
    fun aVp8lWithoutItsSignatureByteIsNull() {
        assertNull(ImageHeader.parse(vp8l(10, 10, signature = 0x2E)))
        assertNull(ImageHeader.parse(vp8l(10, 10, signature = 0x00)))
    }

    @Test
    fun aVp8lVersionAboveZeroIsNull() {
        // The three version bits must be 0 for every bitstream a shipped decoder reads.
        assertNull(ImageHeader.parse(vp8l(10, 10, version = 1)))
        assertNull(ImageHeader.parse(vp8l(10, 10, version = 7)))
    }

    @Test
    fun theAlphaBitDoesNotChangeTheSize() {
        assertEquals(10 to 20, ImageHeader.size(vp8l(10, 20, alpha = true)))
        assertEquals(10 to 20, ImageHeader.size(vp8l(10, 20, alpha = false)))
    }

    @Test
    fun aChunkDeclaredShorterThanItsHeaderIsNull() {
        assertNull(ImageHeader.parse(vp8l(10, 10, declaredLength = 4)))
        assertNull(ImageHeader.parse(vp8x(10, 10, declaredLength = 9)))
        // A declared size past 2^31 reads back negative — also short.
        assertNull(ImageHeader.parse(vp8l(10, 10, declaredLength = -1)))
        // Longer is fine: the bitstream follows the fields we read.
        assertEquals(10 to 10, ImageHeader.size(vp8l(10, 10, declaredLength = 4_000)))
    }

    @Test
    fun truncationAtEveryBoundaryIsNull() {
        // Never reads past `bytes.size`: every prefix short of the full header answers null rather
        // than throwing, which is the guard's whole job on bytes from another process.
        val simple = vp8l(1404, 1685)
        for (n in 0 until 25) assertNull("VP8L prefix of $n bytes", ImageHeader.parse(simple.copyOfRange(0, n)))
        // 25 bytes — preamble + chunk header + signature + 4 packed bytes — is exactly enough.
        assertEquals(1404 to 1685, ImageHeader.size(simple.copyOfRange(0, 25)))
        val extended = vp8x(1404, 1685)
        for (n in 0 until 30) assertNull("VP8X prefix of $n bytes", ImageHeader.parse(extended.copyOfRange(0, n)))
        assertEquals(1404 to 1685, ImageHeader.size(extended.copyOfRange(0, 30)))
    }

    @Test
    fun theDimensionsAreOneBased() {
        // The wire carries width−1 / height−1, so the smallest encodable image is 1×1 and a zero
        // never appears — there is no "zero dimension" case for the parser to refuse.
        assertEquals(1 to 1, ImageHeader.size(vp8l(1, 1)))
        assertEquals(1 to 1, ImageHeader.size(vp8x(1, 1)))
        assertEquals(16384 to 16384, ImageHeader.size(vp8l(16384, 16384)))
    }

    @Test
    fun aSizePastThePageBoundStillParses() {
        // The caller's guard, not the parser's: a parser that refused a legal file would be a
        // worse bug than the one it was guarding against.
        val h = ImageHeader.parse(vp8l(SketchContract.MAX_PAGE_PX + 1, 4))!!
        assertEquals(SketchContract.MAX_PAGE_PX + 1, h.width)
        val x = ImageHeader.parse(vp8x(SketchContract.MAX_PAGE_PX * 2, 4))!!
        assertEquals(SketchContract.MAX_PAGE_PX * 2, x.width)
    }

    @Test
    fun matchesIsExactInBothDimensions() {
        for (bytes in listOf(vp8l(1404, 1685, tail = 500), vp8x(1404, 1685, tail = 500))) {
            assertTrue(ImageHeader.matches(bytes, 1404, 1685))
            assertFalse(ImageHeader.matches(bytes, 1405, 1685))
            assertFalse(ImageHeader.matches(bytes, 1404, 1686))
            // Swapped dimensions are the mistake this guard exists for — a page turned on its side.
            assertFalse(ImageHeader.matches(bytes, 1685, 1404))
        }
    }

    @Test
    fun matchesRefusesWhatCannotBeParsed() {
        assertFalse(ImageHeader.matches(ByteArray(0), 1, 1))
        assertFalse(ImageHeader.matches(ByteArray(40), 1, 1))
        assertFalse(ImageHeader.matches(vp8l(10, 10, tag = "VP8 "), 10, 10))
    }

    @Test
    fun theBodyAfterTheHeaderIsIgnored() {
        // No RIFF-size reconciliation, no body walk: a truncated bitstream is the decoder's to
        // report, and walking megabytes to learn what a decode would tell us is a cost with no buyer.
        assertEquals(64 to 64, ImageHeader.size(vp8l(64, 64, tail = 4096)))
        assertEquals(64 to 64, ImageHeader.size(vp8x(64, 64, tail = 4096)))
    }
}
