package com.symmetricalpalmtree.notesproutsn.extension

import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * **The page bundle** (arc 18 / D1) — the container a [ExporterContract.SOURCE_PAGES] exporter
 * receives through its read fd: every page of the notebook, host-rendered full-fidelity into one
 * encoded image each, in display order. It exists so an exporter that could never receive the
 * `.soil` itself (no key ever crosses) can still deliver a page-faithful document.
 *
 * The wire format, all integers big-endian ([DataOutputStream]'s order):
 *
 * ```
 * "NSPB" (4 ASCII bytes) · int version = 1 · int pageCount ·
 * pageCount × ( int widthPx · int heightPx · int byteLength · byteLength image bytes )
 * ```
 *
 * The image bytes are one platform-decodable encoded image per page (`BitmapFactory` sniffs the
 * header — the container does not name the codec; the host writes WEBP lossy q100 over an opaque
 * RGB_565 bake, the F5 measured recipe). Width/height are the page's own pixel size, carried
 * beside the bytes so a reader can size a PDF page without decoding first.
 *
 * **One page at a time, both sides — the API is the memory rule.** [Writer.writePage] appends one
 * page and keeps nothing; [Reader.readPage] hands back one page and keeps nothing. A whole
 * notebook of full-size images in memory is an OOM on a 3 GB device, so neither end ever holds
 * more than one.
 *
 * **Unmarshal is validation** (the family rule, in stream clothes): [Reader] refuses a wrong
 * magic, an unknown version, and any count or length outside the caps with an [IOException]
 * before allocating for it — the fd comes from the other side of a process boundary.
 */
object PageBundle {

    /** "NSPB" — Notesprout page bundle. */
    val MAGIC: ByteArray = byteArrayOf(0x4E, 0x53, 0x50, 0x42)

    const val VERSION: Int = 1

    /** Most pages one bundle may carry. */
    const val MAX_PAGES: Int = 4096

    /** Largest page edge (px) — far past any real panel, small enough to refuse nonsense. */
    const val MAX_DIMENSION_PX: Int = 32768

    /** Largest encoded image (bytes). A full Manta page bakes to well under this at WEBP q100;
     *  the cap is what stops a corrupt length from asking the reader for a huge allocation. */
    const val MAX_PAGE_BYTES: Int = 32 * 1024 * 1024

    /** One page as the reader hands it back: the page's own pixel size + its encoded image. */
    class Page(val widthPx: Int, val heightPx: Int, val image: ByteArray)

    /**
     * Streams a bundle onto [out] (which it owns and closes). Declare [pageCount] up front — the
     * host knows the page list before it renders — then [writePage] exactly that many times and
     * [close]. Closing short of the declared count throws: a truncated bundle must never read
     * as a finished one.
     */
    class Writer(out: OutputStream, private val pageCount: Int) : Closeable {

        private val stream = DataOutputStream(out.buffered())
        private var written = 0
        private var closed = false

        init {
            require(pageCount in 1..MAX_PAGES) { "$pageCount pages outside 1..$MAX_PAGES" }
            stream.write(MAGIC)
            stream.writeInt(VERSION)
            stream.writeInt(pageCount)
        }

        /** Append one page. [image] is written through and not retained. */
        fun writePage(widthPx: Int, heightPx: Int, image: ByteArray) {
            check(!closed) { "writer is closed" }
            check(written < pageCount) { "all $pageCount pages already written" }
            require(widthPx in 1..MAX_DIMENSION_PX && heightPx in 1..MAX_DIMENSION_PX) {
                "page size ${widthPx}x$heightPx outside 1..$MAX_DIMENSION_PX"
            }
            require(image.isNotEmpty() && image.size <= MAX_PAGE_BYTES) {
                "image of ${image.size} bytes outside 1..$MAX_PAGE_BYTES"
            }
            stream.writeInt(widthPx)
            stream.writeInt(heightPx)
            stream.writeInt(image.size)
            stream.write(image)
            written++
        }

        /** Flush and close. Throws [IOException] if fewer pages than declared were written. */
        override fun close() {
            if (closed) return
            closed = true
            try {
                if (written < pageCount) {
                    throw IOException("bundle closed after $written of $pageCount pages")
                }
                stream.flush()
            } finally {
                runCatching { stream.close() }
            }
        }
    }

    /**
     * Reads a bundle from [input] (which it owns and closes). The header is validated in the
     * constructor; then call [readPage] exactly [pageCount] times. Every violation — wrong magic,
     * unknown version, a count or length outside the caps, a stream that ends early — is an
     * [IOException]; nothing is allocated for a length before that length has passed the cap.
     */
    class Reader(input: InputStream) : Closeable {

        private val stream = DataInputStream(input.buffered())

        val pageCount: Int

        private var read = 0

        init {
            val magic = ByteArray(MAGIC.size)
            try {
                stream.readFully(magic)
            } catch (e: EOFException) {
                throw IOException("not a page bundle: shorter than the magic", e)
            }
            if (!magic.contentEquals(MAGIC)) throw IOException("not a page bundle: wrong magic")
            val version = stream.readInt()
            if (version != VERSION) throw IOException("unknown page-bundle version $version")
            val count = stream.readInt()
            if (count !in 1..MAX_PAGES) throw IOException("$count pages outside 1..$MAX_PAGES")
            pageCount = count
        }

        /** The next page, in display order. Throws [IOException] past the last page — the caller
         *  drives by [pageCount], and reading further is a bug, not an EOF to swallow. */
        fun readPage(): Page {
            if (read >= pageCount) throw IOException("all $pageCount pages already read")
            val width = stream.readInt()
            val height = stream.readInt()
            if (width !in 1..MAX_DIMENSION_PX || height !in 1..MAX_DIMENSION_PX) {
                throw IOException("page size ${width}x$height outside 1..$MAX_DIMENSION_PX")
            }
            val length = stream.readInt()
            if (length !in 1..MAX_PAGE_BYTES) {
                throw IOException("image of $length bytes outside 1..$MAX_PAGE_BYTES")
            }
            val image = ByteArray(length)
            try {
                stream.readFully(image)
            } catch (e: EOFException) {
                throw IOException("bundle ends inside page ${read + 1} of $pageCount", e)
            }
            read++
            return Page(width, height, image)
        }

        override fun close() {
            runCatching { stream.close() }
        }
    }
}
