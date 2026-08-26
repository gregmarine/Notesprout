package com.symmetricalpalmtree.notesproutsn.data.template

/**
 * What an imported picture has to survive before it may become a template row (arc 13 / G4) —
 * **pure arithmetic, no `android.graphics`, JVM-tested**, because these are the numbers that decide
 * whether the app writes a blob it can never read back.
 *
 * The whole of the rule is two sizes and one ceiling:
 *
 *  - **Downscale to the page's own long edge** ([scaledSize]). The library stores the *original*
 *    picture, but "original" cannot mean "whatever the camera produced": a page is 1872 px tall on
 *    a Nomad and every pixel past that is stored, encrypted and decoded for nothing.
 *  - **Never upscale.** A 400 px sketch imported as paper stays 400 px; enlarging it here would
 *    bake in a resample the page-sized render is going to do anyway, better, at the page's size.
 *  - **[MAX_BLOB_BYTES] is a hard refusal**, not a fallback quality. Lossless is what the arc chose
 *    (see the plan's G4 answers), so a picture too grainy to compress is refused with a dialog
 *    rather than silently stored at a quality the user did not ask for.
 *
 * *Why the ceiling exists at all* — the B3 trap: SQLCipher's 8 MiB `CursorWindow` caps any row the
 * app can **read**, and there is nothing stopping it writing a bigger one. A blob over the window
 * inserts fine, lists fine (every listing is blob-free) and then fails the first time the pixels are
 * actually wanted. 6 MiB leaves a third of the window spare for the row's other columns and for a
 * cursor that has more than this row in it.
 */
object TemplateImport {

    /** The most an imported picture's *encoded* bytes may be. Above this it is refused, with a
     *  dialog naming the size — see the class note for where the number comes from. */
    const val MAX_BLOB_BYTES: Int = 6 * 1024 * 1024

    /** The three formats the arc locked. No PDF — that is a new Gradle dependency and its own
     *  decision. Passed to the picker as `EXTRA_MIME_TYPES`. */
    val MIME_TYPES: Array<String> = arrayOf("image/png", "image/jpeg", "image/webp")

    /**
     * The `inSampleSize` for a bounds-first decode: the largest power of two that still leaves the
     * long edge **at or above** [maxEdge], so [scaledSize]'s exact resize is always a downscale.
     *
     * Sampling past [maxEdge] and then enlarging back up would throw away detail and then invent
     * it, which is the one thing a template — all thin rules and fine dots — cannot afford.
     */
    fun sampleSize(srcWidth: Int, srcHeight: Int, maxEdge: Int): Int {
        if (srcWidth <= 0 || srcHeight <= 0 || maxEdge <= 0) return 1
        val longEdge = maxOf(srcWidth, srcHeight)
        var sample = 1
        while (longEdge / (sample * 2) >= maxEdge) sample *= 2
        return sample
    }

    /**
     * The size a decoded picture should be stored at, or **null when it is already small enough**
     * — null means "leave these pixels exactly as they are", which is both the cheap answer and the
     * honest one.
     *
     * Aspect is kept; both edges are at least 1 px, because a 3000 × 2 strip scaled to a 1872 long
     * edge rounds its short edge to zero and `createScaledBitmap` throws on that.
     */
    fun scaledSize(srcWidth: Int, srcHeight: Int, maxEdge: Int): Pair<Int, Int>? {
        if (srcWidth <= 0 || srcHeight <= 0 || maxEdge <= 0) return null
        val longEdge = maxOf(srcWidth, srcHeight)
        if (longEdge <= maxEdge) return null
        val scale = maxEdge.toDouble() / longEdge
        val w = Math.round(srcWidth * scale).toInt().coerceAtLeast(1)
        val h = Math.round(srcHeight * scale).toInt().coerceAtLeast(1)
        return w to h
    }

    /** True when [encodedBytes] may not be stored. */
    fun overCap(encodedBytes: Int): Boolean = encodedBytes > MAX_BLOB_BYTES

    /** `6.0 MB` — the size a refusal dialog says out loud, in the units a file manager uses. */
    fun megabytes(bytes: Int): String = "%.1f MB".format(bytes / 1_000_000.0)

    /**
     * The name to offer for a file called [displayName]: the basename with its extension dropped,
     * with every character the family charset refuses turned into a space and the runs collapsed.
     *
     * Substituting rather than stripping is deliberate — `IMG_0031(2).png` should offer
     * `IMG_0031 2`, not `IMG_00312`, which reads as a different file. [fallback] covers a name that
     * is empty, absent, nothing but punctuation, or one of the two names the family reserves; the
     * result is never blank, but it is still put through `NameRules.validate` by the caller like
     * anything the user typed.
     */
    fun nameFrom(displayName: String?, fallback: String): String {
        val base = displayName.orEmpty().substringAfterLast('/').substringBeforeLast('.')
        val cleaned = buildString(base.length) {
            for (c in base) append(if (ALLOWED.matches(c.toString())) c else ' ')
        }.replace(MULTI_SPACE, " ").trim()
        return if (cleaned.isBlank() || cleaned == "." || cleaned == "..") fallback else cleaned
    }

    /** One character of `NameRules.CHARSET`, kept here so this file stays Android-free. */
    private val ALLOWED = Regex("[a-zA-Z0-9_\\-. ]")
    private val MULTI_SPACE = Regex(" {2,}")
}
