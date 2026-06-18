package com.notesprout.android.data

import kotlinx.serialization.Serializable

/**
 * Where a link points.  Phase 1 supports page/notebook targets only — file and website
 * targets are a future effort and intentionally out of scope.
 *
 * Serialized polymorphically inside [LinkObject] (and carried on [LinkRender] for undo/redo);
 * every subclass is [@Serializable] so kotlinx.serialization can round-trip the sealed hierarchy.
 */
@Serializable
sealed class LinkTarget {
    /** A page in the notebook the link lives in — follow navigates without closing the notebook. */
    @Serializable
    data class CurrentNotebookPage(val pageId: String) : LinkTarget()

    /** Another notebook in general — follow opens it to its last-opened page. */
    @Serializable
    data class OtherNotebook(val notebookId: String) : LinkTarget()

    /** A specific page in another notebook — follow opens it and navigates to [pageId]. */
    @Serializable
    data class OtherNotebookPage(val notebookId: String, val pageId: String) : LinkTarget()
}

/**
 * The visual indicator drawn around a link's union bounding box.
 * Plain enum (no annotation) — matches [LineStyle] / [LineOrientation]; kotlinx.serialization
 * handles enums used inside [@Serializable] classes without an explicit annotation.
 */
enum class LinkChrome { NONE, UNDERLINE, DOTTED_CHEVRON }
