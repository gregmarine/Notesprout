package com.symmetricalpalmtree.notesprout.extension

/** A markdown render whose arguments are outside the contract's caps. Thrown before binding. */
class RenderArgsException(message: String) : ExtensionCallException(message)

/**
 * Host-side caps for the Markdown renderer (arc 4 / H3 — pure, JVM-tested): the outward arguments
 * are checked **before** the bind (and re-applied inward by the Markdown proxy before forwarding —
 * audit row 20), the inward [RenderedImage] header is verified against its declared size and the
 * edge cap. Everything else is [ExtensionCallException] territory (mime, byte count).
 */
object RenderCaps {

    /** The source that may cross: truncated to [ExtensionContract.MAX_MARKDOWN_CHARS]. */
    fun markdown(source: String): String = source.take(ExtensionContract.MAX_MARKDOWN_CHARS)

    /**
     * Throws [RenderArgsException] unless `0 < maxWidthPx ≤ MAX_IMAGE_EDGE_PX`, `dpi > 0` (finite),
     * `maxLines ≥ 0` and `0 ≤ paddingPx ≤ RENDER_PADDING_MAX_PX`.
     */
    fun checkArgs(maxWidthPx: Int, dpi: Float, maxLines: Int, paddingPx: Int) {
        if (maxWidthPx <= 0 || maxWidthPx > ExtensionContract.MAX_IMAGE_EDGE_PX) throw RenderArgsException("maxWidthPx out of range ($maxWidthPx)")
        if (!(dpi > 0f) || dpi.isNaN() || dpi.isInfinite()) throw RenderArgsException("dpi must be > 0 ($dpi)")
        if (maxLines < 0) throw RenderArgsException("maxLines must be >= 0 ($maxLines)")
        if (paddingPx !in 0..ExtensionContract.RENDER_PADDING_MAX_PX) throw RenderArgsException("paddingPx out of range ($paddingPx)")
    }

    /**
     * Inward: the declared image size must be positive, within [ExtensionContract.MAX_IMAGE_EDGE_PX]
     * on both sides, and equal to what the encoded header says ([headerSize] — null when the bytes are
     * not a decodable image). Returns the message of the violation, or null when the payload is sound.
     */
    fun imageProblem(declaredWidth: Int, declaredHeight: Int, headerSize: Pair<Int, Int>?): String? {
        if (declaredWidth <= 0 || declaredHeight <= 0) return "non-positive declared size ${declaredWidth}x$declaredHeight"
        if (declaredWidth > ExtensionContract.MAX_IMAGE_EDGE_PX || declaredHeight > ExtensionContract.MAX_IMAGE_EDGE_PX) {
            return "declared size ${declaredWidth}x$declaredHeight exceeds MAX_IMAGE_EDGE_PX"
        }
        if (headerSize == null) return "payload is not a decodable image"
        if (headerSize.first != declaredWidth || headerSize.second != declaredHeight) {
            return "payload is ${headerSize.first}x${headerSize.second}, declared ${declaredWidth}x$declaredHeight"
        }
        return null
    }

    /** Inward mime + byte-count rule shared with the templates path: the message of the violation, or null. */
    fun bytesProblem(mimeType: String?, byteCount: Int, regionSize: Int): String? {
        if (mimeType != ExtensionContract.MIME_WEBP) return "unexpected mime type '$mimeType'"
        if (byteCount <= 0 || byteCount > ExtensionContract.MAX_RENDER_BYTES || byteCount > regionSize) {
            return "bad byte count $byteCount (region $regionSize)"
        }
        return null
    }
}
