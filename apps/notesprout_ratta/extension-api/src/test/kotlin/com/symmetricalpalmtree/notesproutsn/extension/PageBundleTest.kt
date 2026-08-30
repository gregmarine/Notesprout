package com.symmetricalpalmtree.notesproutsn.extension

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException

/** The container round trip and its refusals — pure `java.io`, the whole point of the format. */
class PageBundleTest {

    private fun bundleOf(vararg pages: Triple<Int, Int, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        PageBundle.Writer(out, pages.size).use { w ->
            for ((width, height, image) in pages) w.writePage(width, height, image)
        }
        return out.toByteArray()
    }

    @Test
    fun roundTripsThreePages() {
        val images = listOf(byteArrayOf(1), byteArrayOf(2, 3), ByteArray(1024) { it.toByte() })
        val bytes = bundleOf(
            Triple(1404, 1872, images[0]),
            Triple(1920, 2560, images[1]),
            Triple(7, 9, images[2]),
        )
        PageBundle.Reader(ByteArrayInputStream(bytes)).use { r ->
            assertEquals(3, r.pageCount)
            val first = r.readPage()
            assertEquals(1404, first.widthPx)
            assertEquals(1872, first.heightPx)
            assertArrayEquals(images[0], first.image)
            assertArrayEquals(images[1], r.readPage().image)
            assertArrayEquals(images[2], r.readPage().image)
            // Driving past pageCount is a caller bug, not an EOF to swallow.
            assertThrows(IOException::class.java) { r.readPage() }
        }
    }

    @Test
    fun writerRefusesBadShapes() {
        val out = ByteArrayOutputStream()
        assertThrows(IllegalArgumentException::class.java) { PageBundle.Writer(out, 0) }
        assertThrows(IllegalArgumentException::class.java) { PageBundle.Writer(out, PageBundle.MAX_PAGES + 1) }
        PageBundle.Writer(ByteArrayOutputStream(), 1).let { w ->
            assertThrows(IllegalArgumentException::class.java) { w.writePage(0, 10, byteArrayOf(1)) }
            assertThrows(IllegalArgumentException::class.java) {
                w.writePage(10, PageBundle.MAX_DIMENSION_PX + 1, byteArrayOf(1))
            }
            assertThrows(IllegalArgumentException::class.java) { w.writePage(10, 10, ByteArray(0)) }
            w.writePage(10, 10, byteArrayOf(1))
            // The declared count is a contract in both directions.
            assertThrows(IllegalStateException::class.java) { w.writePage(10, 10, byteArrayOf(1)) }
            w.close()
        }
    }

    @Test
    fun closingShortOfTheDeclaredCountThrows() {
        val w = PageBundle.Writer(ByteArrayOutputStream(), 2)
        w.writePage(10, 10, byteArrayOf(1))
        assertThrows(IOException::class.java) { w.close() }
    }

    @Test
    fun readerRefusesWrongMagicAndVersion() {
        assertThrows(IOException::class.java) {
            PageBundle.Reader(ByteArrayInputStream(byteArrayOf(1, 2)))
        }
        assertThrows(IOException::class.java) {
            PageBundle.Reader(ByteArrayInputStream("SOIL0000".toByteArray()))
        }
        val badVersion = ByteArrayOutputStream().also { bos ->
            DataOutputStream(bos).apply { write(PageBundle.MAGIC); writeInt(99); writeInt(1) }
        }.toByteArray()
        assertThrows(IOException::class.java) { PageBundle.Reader(ByteArrayInputStream(badVersion)) }
    }

    @Test
    fun readerRefusesCapViolationsBeforeAllocating() {
        fun header(count: Int, block: DataOutputStream.() -> Unit = {}): ByteArray =
            ByteArrayOutputStream().also { bos ->
                DataOutputStream(bos).apply { write(PageBundle.MAGIC); writeInt(PageBundle.VERSION); writeInt(count); block() }
            }.toByteArray()

        assertThrows(IOException::class.java) { PageBundle.Reader(ByteArrayInputStream(header(0))) }
        assertThrows(IOException::class.java) {
            PageBundle.Reader(ByteArrayInputStream(header(PageBundle.MAX_PAGES + 1)))
        }
        // A corrupt image length past the cap is refused without a huge allocation being asked for.
        val bogusLength = header(1) { writeInt(10); writeInt(10); writeInt(PageBundle.MAX_PAGE_BYTES + 1) }
        PageBundle.Reader(ByteArrayInputStream(bogusLength)).use { r ->
            assertThrows(IOException::class.java) { r.readPage() }
        }
        val bogusDimension = header(1) { writeInt(-5); writeInt(10); writeInt(1); write(1) }
        PageBundle.Reader(ByteArrayInputStream(bogusDimension)).use { r ->
            assertThrows(IOException::class.java) { r.readPage() }
        }
    }

    @Test
    fun truncatedBundleThrowsInsideThePage() {
        val whole = bundleOf(Triple(10, 10, ByteArray(100) { 7 }))
        val cut = whole.copyOf(whole.size - 40)
        PageBundle.Reader(ByteArrayInputStream(cut)).use { r ->
            assertThrows(IOException::class.java) { r.readPage() }
        }
    }
}
