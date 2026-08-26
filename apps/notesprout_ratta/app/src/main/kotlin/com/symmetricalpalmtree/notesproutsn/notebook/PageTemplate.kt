package com.symmetricalpalmtree.notesproutsn.notebook

import com.symmetricalpalmtree.notesproutsn.data.soil.TemplateDigest
import com.symmetricalpalmtree.notesproutsn.data.template.TemplateToken

/**
 * Which template row a page should be pointed at when the user re-papers it (arc 12, generalised
 * to the library in arc 13 / G3) — **pure arithmetic over blob-free digests**, so the one decision
 * that costs pixels is JVM-testable.
 *
 * A template row is *shared paper*, not a page's property: every page created with the notebook
 * points at the same row, and re-papering a page must not stack another megabyte of identical
 * WEBP beside it. So the change looks for a row this notebook already holds that **is** the wanted
 * paper at the page's exact size, and only mints a new one when there is none. That also makes the
 * common there-and-back — Lined → Grid → Lined — free: the original row is still there (nothing
 * ever soft-deletes a template), so the second change finds it and re-points at it.
 *
 * Identity is `token + page size`, deliberately not the pixels. Arc 12 matched on [TemplateKind],
 * which only worked while the four built-ins were the only paper there was; the [TemplateToken]
 * vocabulary is the same rule widened to say `IMG#…` as well, and the built-ins' tokens are their
 * old names unchanged. A byte-identical row arriving from another notebook was already deduped by
 * content on the way in ([NotebookSession.resolveTemplate] → [PageClip.matchTemplate]), so the only
 * row that can pass this test while looking different is one authored at the same page size but a
 * different panel dpi — which would mean two Supernotes with identical screen pixels and different
 * densities. That device does not exist in the family, and the cost if it ever did is a rule 0.1 mm
 * off.
 */
object PageTemplate {

    /**
     * The id of a live template row in [digests] that already carries [token] at [widthPx] ×
     * [heightPx], or null when the caller must render and store a fresh one.
     *
     * An **empty** token is always null — blank paper has *no* template row and its `refId` is `""`;
     * there is nothing to reuse and nothing to mint.
     *
     * A row with no pixels (`blobLength` null or 0) is refused: it names a paper it cannot draw,
     * so re-pointing at it would blank the page while claiming otherwise.
     *
     * [prefer] — the page's *current* template id — wins among equal matches, which is how "pick
     * the card the browser already ticked" stays a true no-op. A notebook can hold two rows of one
     * paper at one size (a page pasted from a notebook whose panel had a different density, so the
     * content dedupe found no match), and without this the page would be re-pointed at the
     * identical-looking twin and a pointless step pushed onto the undo stack.
     */
    fun reusableId(
        digests: List<TemplateDigest>,
        token: String,
        widthPx: Int,
        heightPx: Int,
        prefer: String? = null,
    ): String? {
        if (token.isEmpty()) return null
        val matches = digests.filter { d ->
            d.text == token &&
                (d.blobLength ?: 0) > 0 &&
                (d.width ?: 0f).toInt() == widthPx &&
                (d.height ?: 0f).toInt() == heightPx
        }
        return matches.firstOrNull { it.id == prefer }?.id ?: matches.firstOrNull()?.id
    }

    /**
     * The token a page's template row carries, from the same blob-free digests. An empty
     * [templateId] is `""` — that is what blank *is* in the format, not a missing answer, and the
     * Blank card ticks on it.
     *
     * Null means **unknown**: the row has vanished, or it carries no `text` at all. The browser
     * ticks nothing for null rather than guessing — claiming "Blank" for paper the user can see on
     * the glass would be a lie they cannot check. A token this build does not *recognise* (paper
     * authored by a later version of the family) is returned as it stands and simply matches no
     * card, which is the same silence by a shorter road.
     */
    fun tokenOf(digests: List<TemplateDigest>, templateId: String): String? {
        if (templateId.isEmpty()) return ""
        return digests.firstOrNull { it.id == templateId }?.text
    }
}
