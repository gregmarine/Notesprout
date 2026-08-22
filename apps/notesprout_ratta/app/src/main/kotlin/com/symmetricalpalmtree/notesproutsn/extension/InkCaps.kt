package com.symmetricalpalmtree.notesproutsn.extension

/** Ink that may not cross the boundary: over the `MAX_INK_*` caps, malformed, or a bad size. Thrown before binding. */
class InkTooLargeException(message: String) : ExtensionCallException(message)

/**
 * Host-side enforcement of the recognizer's outward caps — pure, JVM-tested, run **before** any bind
 * (ink is capped before it crosses and re-checked by the extension after). Every violation is an
 * [InkTooLargeException]; the extension re-checks the same caps and answers `IllegalArgumentException`.
 */
object InkCaps {

    /**
     * Throws [InkTooLargeException] unless [strokes] fits `MAX_INK_STROKES` / `MAX_INK_POINTS`, every
     * stroke is non-empty with equal-length x/y arrays, and [width]/[height] are positive.
     */
    fun check(strokes: List<InkStroke>, width: Float, height: Float) {
        if (strokes.size > ExtensionContract.MAX_INK_STROKES) {
            throw InkTooLargeException("${strokes.size} strokes > ${ExtensionContract.MAX_INK_STROKES}")
        }
        var points = 0L
        for (s in strokes) {
            // InkStroke's constructor already rejects empty / mismatched arrays; re-stated here so a
            // future parcel-side relaxation can't silently widen what leaves the host.
            if (s.x.isEmpty() || s.x.size != s.y.size) throw InkTooLargeException("malformed stroke")
            points += s.size
        }
        if (points > ExtensionContract.MAX_INK_POINTS) {
            throw InkTooLargeException("$points points > ${ExtensionContract.MAX_INK_POINTS}")
        }
        if (!(width > 0f) || !(height > 0f)) throw InkTooLargeException("non-positive size ${width}x$height")
    }

    /** The tail of [preContext] that may cross: at most `MAX_PRECONTEXT_CHARS`. */
    fun preContext(preContext: String): String = preContext.takeLast(ExtensionContract.MAX_PRECONTEXT_CHARS)

    /** Inward status: anything outside `0..3` is [RecognizerStatus.UNAVAILABLE]. */
    fun status(raw: Int): Int =
        if (raw in RecognizerStatus.READY..RecognizerStatus.UNAVAILABLE) raw else RecognizerStatus.UNAVAILABLE

    /** Inward text: null → "", truncated to `MAX_RECOGNIZED_CHARS`. */
    fun text(raw: String?): String = (raw ?: "").take(ExtensionContract.MAX_RECOGNIZED_CHARS)
}
