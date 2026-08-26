package com.symmetricalpalmtree.notesproutsn.data.template

import java.security.MessageDigest

/**
 * The `.soil` `template` row's **token** — the string in its `text` column, and the whole of what
 * "this is the same paper" means inside a notebook file (arc 13 / G3).
 *
 * Arc 12 matched a page's paper by [TemplateKind]; that only ever worked because the four built-ins
 * were the only paper there was. A library that can hold imported pictures needs one vocabulary
 * wide enough for both, and this is it:
 *
 *  - `""` — **blank**. Not a token at all: a blank page has no template row and its `refId` is `""`.
 *  - `LINED` / `DOTTED` / `GRID` — the built-ins, spelled exactly as before. Every existing file and
 *    every Paper build still reads them, and **nothing about them changes**.
 *  - `IMG#<8 hex>` — an imported picture (G4), identified by a digest of what it draws.
 *
 * **The digest covers the fit mode as well as the bytes**, which the locked wording ("8 hex of the
 * image bytes") did not say and correctness needs: fit is what turns one stored picture into
 * page-sized pixels, so the same photo fitted and stretched are two papers. Digesting the bytes
 * alone would let a page that asked for the stretched one be re-pointed at the fitted row already
 * in the file, and the user would see the wrong paper with no way to ask again.
 *
 * Pure Kotlin — `MessageDigest` is on both the JVM and Android — so the identity rule that decides
 * whether a megabyte of WEBP gets written is JVM-testable.
 */
object TemplateToken {

    /** What every imported-picture token starts with. */
    const val IMAGE_PREFIX = "IMG#"

    /** How many hex characters follow [IMAGE_PREFIX] — 4 bytes of SHA-256. */
    const val IMAGE_DIGEST_CHARS = 8

    /** The token for one of the app's own papers. [TemplateKind.BLANK] has none: it is `""`. */
    fun of(kind: TemplateKind): String = if (kind == TemplateKind.BLANK) "" else kind.name

    /**
     * The token for an imported picture: [IMAGE_PREFIX] plus the first 4 bytes of
     * `SHA-256(fit ‖ bytes)`, lowercase hex.
     *
     * The fit byte goes **first** so it can never be mistaken for image data, and it is folded in
     * for the reason in the class note.
     */
    fun ofImage(bytes: ByteArray, fit: Int): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(byteArrayOf((fit and 0xFF).toByte()))
        digest.update(bytes)
        val hash = digest.digest()
        val hex = StringBuilder(IMAGE_DIGEST_CHARS)
        for (i in 0 until IMAGE_DIGEST_CHARS / 2) hex.append("%02x".format(hash[i]))
        return IMAGE_PREFIX + hex
    }

    /** True for a token this build recognises as an imported picture. Shape only — the bytes it
     *  names may be in another notebook, or nowhere. */
    fun isImage(token: String): Boolean =
        token.startsWith(IMAGE_PREFIX) && token.length == IMAGE_PREFIX.length + IMAGE_DIGEST_CHARS

    /**
     * The built-in [token] names, or null when it names something else — an imported picture, or
     * paper authored by a later version of the family.
     *
     * `""` answers [TemplateKind.BLANK], because in this format blank *is* the absence of a row,
     * not a missing answer.
     */
    fun kindOf(token: String): TemplateKind? {
        if (token.isEmpty()) return TemplateKind.BLANK
        return TemplateKind.entries.firstOrNull { it != TemplateKind.BLANK && it.name == token }
    }
}
