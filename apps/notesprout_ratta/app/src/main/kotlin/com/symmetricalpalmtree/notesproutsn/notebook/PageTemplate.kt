package com.symmetricalpalmtree.notesproutsn.notebook

import com.symmetricalpalmtree.notesproutsn.data.soil.TemplateDigest
import com.symmetricalpalmtree.notesproutsn.data.template.TemplateKind

/**
 * Which template row a page should be pointed at when the user re-papers it (arc 12) — **pure
 * arithmetic over blob-free digests**, so the one decision that costs pixels is JVM-testable.
 *
 * A template row is *shared paper*, not a page's property: every page created with the notebook
 * points at the same row, and re-papering a page must not stack another megabyte of identical
 * WEBP beside it. So the change looks for a row this notebook already holds that **is** the wanted
 * kind at the page's exact size, and only mints a new one when there is none. That also makes the
 * common there-and-back — Lined → Grid → Lined — free: the original row is still there (nothing
 * ever soft-deletes a template), so the second change finds it and re-points at it.
 *
 * Identity is `kind + page size`, deliberately not the pixels. A byte-identical row arriving from
 * another notebook was already deduped by content on the way in
 * ([NotebookSession.resolveTemplate] → [PageClip.matchTemplate]), so the only row that can pass
 * this test while looking different is one authored at the same page size but a different panel
 * dpi — which would mean two Supernotes with identical screen pixels and different densities.
 * That device does not exist in the family, and the cost if it ever did is a rule 0.1 mm off.
 */
object PageTemplate {

    /**
     * The id of a live template row in [digests] that already carries [kind] at [widthPx] ×
     * [heightPx], or null when the caller must render and store a fresh one.
     *
     * [TemplateKind.BLANK] is always null — a blank page has **no** template row and its `refId`
     * is `""`; there is nothing to reuse and nothing to mint.
     *
     * A row with no pixels (`blobLength` null or 0) is refused: it names a paper it cannot draw,
     * so re-pointing at it would blank the page while claiming otherwise.
     *
     * [prefer] — the page's *current* template id — wins among equal matches, which is how "pick
     * the kind the sheet already ticked" stays a true no-op. A notebook can hold two rows of one
     * kind at one size (a page pasted from a notebook whose panel had a different density, so the
     * content dedupe found no match), and without this the page would be re-pointed at the
     * identical-looking twin and a pointless step pushed onto the undo stack.
     */
    fun reusableId(
        digests: List<TemplateDigest>,
        kind: TemplateKind,
        widthPx: Int,
        heightPx: Int,
        prefer: String? = null,
    ): String? {
        if (kind == TemplateKind.BLANK) return null
        val matches = digests.filter { d ->
            d.text == kind.name &&
                (d.blobLength ?: 0) > 0 &&
                (d.width ?: 0f).toInt() == widthPx &&
                (d.height ?: 0f).toInt() == heightPx
        }
        return matches.firstOrNull { it.id == prefer }?.id ?: matches.firstOrNull()?.id
    }

    /**
     * The kind a page's template row is showing, from the same blob-free digests. An empty
     * [templateId] is [TemplateKind.BLANK] — that is what blank *is* in the format, not a missing
     * answer.
     *
     * Null means **unknown**: the row has vanished, or its `text` is not one of this build's four
     * built-ins (a template authored by another member of the family, or by a later version). The
     * picker shows no check mark for null rather than guessing — claiming "Blank" for paper the
     * user can see on the glass would be a lie they cannot check.
     */
    fun kindOf(digests: List<TemplateDigest>, templateId: String): TemplateKind? {
        if (templateId.isEmpty()) return TemplateKind.BLANK
        val text = digests.firstOrNull { it.id == templateId }?.text ?: return null
        return TemplateKind.entries.firstOrNull { it.name == text }
    }
}
