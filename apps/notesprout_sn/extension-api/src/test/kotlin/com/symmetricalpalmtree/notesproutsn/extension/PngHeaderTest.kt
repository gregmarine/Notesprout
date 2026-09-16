package com.symmetricalpalmtree.notesproutsn.extension

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The PNG header guard (arc 43 / K2), proved on hand-built bytes — which is the whole reason it
 * reads the header by hand instead of asking `BitmapFactory`: every boundary here is a laptop test.
 */
class PngHeaderTest {

    private val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    /** Signature + a well-formed `IHDR` chunk (length, type, 13 data bytes, CRC) + [tail] bytes. */
    private fun png(
        width: Int,
        height: Int,
        bitDepth: Int = 8,
        colorType: Int = 6,   // RGBA — what an ARGB_8888 sketch encodes to
        type: String = "IHDR",
        declaredLength: Int = 13,
        tail: Int = 0,
    ): ByteArray {
        val out = ArrayList<Byte>()
        out += signature.toList()
        out += be32(declaredLength)
        out += type.map { it.code.toByte() }
        out += be32(width)
        out += be32(height)
        out += bitDepth.toByte()
        out += colorType.toByte()
        out += 0.toByte()   // compression
        out += 0.toByte()   // filter
        out += 0.toByte()   // interlace
        out += be32(0)      // CRC — never checked, see the class note
        repeat(tail) { out += 0x7F.toByte() }
        return out.toByteArray()
    }

    private fun be32(v: Int): List<Byte> = listOf(
        (v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte(),
    )

    @Test
    fun aWellFormedHeaderParses() {
        val ihdr = PngHeader.parse(png(1404, 1685))!!
        assertEquals(1404, ihdr.width)
        assertEquals(1685, ihdr.height)
        assertEquals(8, ihdr.bitDepth)
        assertEquals(6, ihdr.colorType)
    }

    @Test
    fun sizeDerivesFromParse() {
        assertEquals(1404 to 1685, PngHeader.size(png(1404, 1685)))
        assertNull(PngHeader.size(ByteArray(0)))
    }

    @Test
    fun anEmptyArrayIsNull() {
        assertNull(PngHeader.parse(ByteArray(0)))
    }

    @Test
    fun aWrongSignatureIsNull() {
        val bad = png(10, 10)
        bad[0] = 0x88.toByte()
        assertNull(PngHeader.parse(bad))
        val alsoBad = png(10, 10)
        alsoBad[7] = 0x00
        assertNull(PngHeader.parse(alsoBad))
    }

    @Test
    fun aFirstChunkThatIsNotIhdrIsNull() {
        // A legal PNG always opens with IHDR; anything else means we are not reading what we think.
        assertNull(PngHeader.parse(png(10, 10, type = "IDAT")))
        assertNull(PngHeader.parse(png(10, 10, type = "iHDR")))
    }

    @Test
    fun anIhdrOfTheWrongDeclaredLengthIsNull() {
        assertNull(PngHeader.parse(png(10, 10, declaredLength = 12)))
        assertNull(PngHeader.parse(png(10, 10, declaredLength = 14)))
    }

    @Test
    fun truncationAtEveryBoundaryIsNull() {
        // Never reads past `bytes.size`: every prefix short of the full header answers null rather
        // than throwing, which is the guard's whole job on bytes from another process.
        val full = png(1404, 1685)
        for (n in 0 until 29) assertNull("prefix of $n bytes", PngHeader.parse(full.copyOfRange(0, n)))
        // 29 bytes — signature + length + type + the 13 data bytes — is exactly enough.
        assertEquals(1404 to 1685, PngHeader.size(full.copyOfRange(0, 29)))
    }

    @Test
    fun zeroOrNegativeDimensionsAreNull() {
        assertNull(PngHeader.parse(png(0, 10)))
        assertNull(PngHeader.parse(png(10, 0)))
        // A width past 2^31 reads back negative; either way it is not a PNG we will decode.
        assertNull(PngHeader.parse(png(-1, 10)))
    }

    @Test
    fun aSizePastThePageBoundStillParses() {
        // The caller's guard, not the parser's: a parser that refused a legal file would be a
        // worse bug than the one it was guarding against.
        val ihdr = PngHeader.parse(png(SketchContract.MAX_PAGE_PX + 1, 4))!!
        assertEquals(SketchContract.MAX_PAGE_PX + 1, ihdr.width)
    }

    @Test
    fun matchesIsExactInBothDimensions() {
        val bytes = png(1404, 1685, tail = 500)
        assertTrue(PngHeader.matches(bytes, 1404, 1685))
        assertFalse(PngHeader.matches(bytes, 1405, 1685))
        assertFalse(PngHeader.matches(bytes, 1404, 1686))
        // Swapped dimensions are the mistake this guard exists for — a page turned on its side.
        assertFalse(PngHeader.matches(bytes, 1685, 1404))
    }

    @Test
    fun matchesRefusesWhatCannotBeParsed() {
        assertFalse(PngHeader.matches(ByteArray(0), 1, 1))
        assertFalse(PngHeader.matches(ByteArray(40), 1, 1))
        assertFalse(PngHeader.matches(png(10, 10, type = "IDAT"), 10, 10))
    }

    @Test
    fun theBodyAfterTheHeaderIsIgnored() {
        // No CRC check, no body walk: a corrupt body is the decoder's to report, and re-hashing
        // megabytes to learn what a decode would tell us is a cost with no buyer.
        assertEquals(64 to 64, PngHeader.size(png(64, 64, tail = 4096)))
    }
}
