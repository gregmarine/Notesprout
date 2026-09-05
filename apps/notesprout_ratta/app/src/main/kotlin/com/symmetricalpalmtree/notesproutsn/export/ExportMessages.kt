package com.symmetricalpalmtree.notesproutsn.export

import androidx.annotation.StringRes
import com.symmetricalpalmtree.notesproutsn.R

/**
 * **What the screen says when a preparation refuses** — one table per preparer, kept together
 * because they are one table read four ways.
 *
 * Each of the four ways into a stream ([ExportArtifact]'s keyed copy, [ExportRender]'s page bake,
 * [ExportText]'s document assembly, [DocumentPdfRender]'s document bake) owns its own `Problem`
 * enum, deliberately: a preparer must be able to refuse for a reason the others have no word for,
 * and a shared enum would grow a member per path and mean less each time. But most of the reasons
 * are the *same* reason arrived at by another road — a live writer, a locked library, a file that
 * is gone — and they owe the user the same sentence. Side by side, that stays true by inspection;
 * spread across four functions in the screen it drifted the moment a fifth road appeared (arc 19 /
 * M9, which added two of them at once).
 *
 * It lives here rather than in [ExportActivity] for the plainer reason too: the screen is the
 * family's longest file, and a `when` over an enum is the least screen-like thing in it.
 *
 * Resource ids only — no `Context`, no `getString`. The screen still does the resolving, so a
 * sentence that needs an argument (there is none yet) stays its business.
 */
object ExportMessages {

    /** The cold cache copy's refusals (arc 15 / E1). */
    @StringRes
    fun of(problem: ExportArtifact.Problem): Int = when (problem) {
        ExportArtifact.Problem.IN_USE -> R.string.export_in_use_body
        ExportArtifact.Problem.NO_KEY -> R.string.export_locked_body
        ExportArtifact.Problem.LOCKED -> R.string.export_notebook_locked_body
        ExportArtifact.Problem.MISSING -> R.string.export_missing_body
        ExportArtifact.Problem.UNREADABLE -> R.string.export_unreadable_body
        ExportArtifact.Problem.COPY_FAILED -> R.string.export_prepare_failed_body
    }

    /** The page bake's refusals (arc 18 / D1) — what went wrong, and that the notebook is as it
     *  was. Four of the eight are the copy's own refusals by another road, and say the same thing. */
    @StringRes
    fun of(problem: ExportRender.Problem): Int = when (problem) {
        ExportRender.Problem.IN_USE -> R.string.export_in_use_body
        ExportRender.Problem.NO_KEY -> R.string.export_locked_body
        ExportRender.Problem.LOCKED -> R.string.export_notebook_locked_body
        ExportRender.Problem.MISSING -> R.string.export_missing_body
        ExportRender.Problem.UNREADABLE -> R.string.export_unreadable_body
        ExportRender.Problem.EMPTY -> R.string.export_empty_body
        ExportRender.Problem.DAMAGED -> R.string.export_damaged_body
        ExportRender.Problem.TOO_LONG -> R.string.export_too_long_body
        ExportRender.Problem.RENDER_FAILED -> R.string.export_render_failed_body
    }

    /** The document assembly's refusals (arc 19 / M9). Four of the five are the copy's again; only
     *  a notebook with nothing written in it is new. */
    @StringRes
    fun of(problem: ExportText.Problem): Int = when (problem) {
        ExportText.Problem.IN_USE -> R.string.export_in_use_body
        ExportText.Problem.NO_KEY -> R.string.export_locked_body
        ExportText.Problem.LOCKED -> R.string.export_notebook_locked_body
        ExportText.Problem.MISSING -> R.string.export_missing_body
        ExportText.Problem.UNREADABLE -> R.string.export_unreadable_body
        ExportText.Problem.NO_DOCUMENT -> R.string.export_no_document_body
    }

    /** The document bake's refusals (arc 19 / M9) — the page bake's table with the document's two
     *  differences: nothing written takes the assembly's sentence, and there is no "no pages"
     *  refusal, because a document does not need any. */
    @StringRes
    fun of(problem: DocumentPdfRender.Problem): Int = when (problem) {
        DocumentPdfRender.Problem.IN_USE -> R.string.export_in_use_body
        DocumentPdfRender.Problem.NO_KEY -> R.string.export_locked_body
        DocumentPdfRender.Problem.LOCKED -> R.string.export_notebook_locked_body
        DocumentPdfRender.Problem.MISSING -> R.string.export_missing_body
        DocumentPdfRender.Problem.UNREADABLE -> R.string.export_unreadable_body
        DocumentPdfRender.Problem.NO_DOCUMENT -> R.string.export_no_document_body
        DocumentPdfRender.Problem.DAMAGED -> R.string.export_damaged_body
        DocumentPdfRender.Problem.TOO_LONG -> R.string.export_too_long_body
        DocumentPdfRender.Problem.RENDER_FAILED -> R.string.export_render_failed_body
    }
}
