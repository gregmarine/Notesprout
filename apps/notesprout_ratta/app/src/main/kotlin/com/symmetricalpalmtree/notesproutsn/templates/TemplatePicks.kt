package com.symmetricalpalmtree.notesproutsn.templates

import com.symmetricalpalmtree.notesproutsn.data.index.IndexRepository
import com.symmetricalpalmtree.notesproutsn.data.template.PaperSource
import com.symmetricalpalmtree.notesproutsn.data.template.TemplateFit
import com.symmetricalpalmtree.notesproutsn.data.template.TemplateKind

/**
 * Turning a [TemplatePick] — which names a *card* — into [PaperSource], which is paper that can be
 * drawn (arc 13 / G3).
 *
 * It is its own small file because both of the hosts that create pixels need it and neither owns
 * it: the New Notebook screen bakes the first page's template, and the notebook re-papers a page.
 * The read is the pick's whole cost, and it happens here, once, on IO.
 */
object TemplatePicks {

    /**
     * The paper [pick] stands for, or **null** when a static row has gone (deleted, or never there)
     * or holds nothing this build can draw.
     *
     * Null is a *failure*, not blank paper: a pick whose row vanished under the user's finger must
     * leave the page as it was and say so, never quietly wipe it.
     */
    suspend fun paper(repo: IndexRepository, pick: TemplatePick): PaperSource? = when (pick) {
        TemplatePick.Blank -> PaperSource.Blank
        is TemplatePick.BuiltIn -> PaperSource.BuiltIn(pick.kind)
        is TemplatePick.Static -> {
            val row = repo.templateRow(pick.id)
            val kind = TemplateKind.entries.firstOrNull { it.name == row?.templateKind }
            when {
                row == null -> null
                // A row filed under one of the built-in kinds is that paper, drawn not stored — it
                // costs no pixels and lands right on any page size.
                kind != null && kind != TemplateKind.BLANK -> PaperSource.BuiltIn(kind)
                row.blob != null -> PaperSource.Image(row.blob, TemplateFit.sanitize(row.flags))
                else -> null
            }
        }
    }

    /** The index `templateKind` a notebook created from [pick] records as its birth paper. It is a
     *  record of how the notebook started, not a pointer: the pixels are already in the file. */
    fun birthKind(pick: TemplatePick): String = when (pick) {
        TemplatePick.Blank -> TemplateKind.BLANK.name
        is TemplatePick.BuiltIn -> pick.kind.name
        is TemplatePick.Static -> TemplateLibrary.KIND_IMAGE
    }
}
