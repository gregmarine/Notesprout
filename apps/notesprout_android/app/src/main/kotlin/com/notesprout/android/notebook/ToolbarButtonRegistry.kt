package com.notesprout.android.notebook

import androidx.annotation.DrawableRes
import androidx.annotation.IdRes
import com.notesprout.android.R

/**
 * Single source of truth mapping each customizable toolbar button to a **stable string key**.
 *
 * Each [ButtonSpec] carries the key, the `R.id` of its `AppCompatImageButton` in
 * `activity_notebook.xml`, its icon, a human label (for the customize dialog), its **group**
 * (consecutive buttons whose group differs get an auto-divider between them), and whether it is
 * **pinned** (Close and the Customize gear — always present, can never be hidden).
 *
 * KEY STABILITY RULE: keys are persisted in [com.notesprout.android.data.toolbar.ToolbarConfig].
 * They are **append-only** and must never change once shipped. Adding a button means appending a
 * new spec; never renaming or reordering existing keys (display order is driven by the persisted
 * config, not this list).
 *
 * The XML still declares every button once and `NotebookActivity` wires the listeners; this
 * registry only describes them. [ToolbarLayoutManager] moves (never clones) the existing views.
 */
object ToolbarButtonRegistry {

    // Group identifiers — buttons sharing a group sit together with no divider between them.
    private const val GROUP_FILE = "file"        // close, recents
    private const val GROUP_NOTEBOOK = "notebook" // toc, cover, export, pin
    private const val GROUP_TOOLS = "tools"       // pen, eraser, lasso eraser, erase all, text, lines, lasso
    private const val GROUP_HISTORY = "history"   // undo, redo
    private const val GROUP_PAGE_VIEW = "pageView" // template, page index
    private const val GROUP_PAGE_EDIT = "pageEdit" // insert before/after, delete, copy, paste
    private const val GROUP_SETTINGS = "settings"  // customize toolbar (gear)

    /** The stable key of the pinned Close button — always present, never hideable. */
    const val PINNED_KEY = "close"

    /**
     * The stable key of the Customize-Toolbar gear. **Pinned** (like Close): always present, never
     * hideable. It is the only entry point to the customize dialog — if it could be hidden the user
     * would lose all access to toolbar customization (including the mini toggle). Force-included in
     * mini mode and force-retained in full mode (see [ToolbarLayoutManager.resolveVisibleKeys]).
     */
    const val SETTINGS_KEY = "toolbarSettings"

    data class ButtonSpec(
        val key: String,
        @IdRes val viewId: Int,
        @DrawableRes val iconRes: Int,
        val label: String,
        val group: String,
        val pinned: Boolean = false,
    )

    /**
     * Every customizable button, in the original left-to-right XML order. This order seeds
     * [DEFAULT_ORDER]; the live order comes from the persisted config.
     */
    val SPECS: List<ButtonSpec> = listOf(
        ButtonSpec(PINNED_KEY, R.id.btnClose, R.drawable.ic_close, "Close", GROUP_FILE, pinned = true),
        ButtonSpec("recents", R.id.btnRecents, R.drawable.ic_clock, "Recents", GROUP_FILE),
        ButtonSpec("toc", R.id.btnToc, R.drawable.ic_toc, "Table of Contents", GROUP_NOTEBOOK),
        ButtonSpec("cover", R.id.btnCover, R.drawable.ic_polaroid, "Set Cover", GROUP_NOTEBOOK),
        ButtonSpec("export", R.id.btnExport, R.drawable.ic_export, "Export", GROUP_NOTEBOOK),
        ButtonSpec("pin", R.id.btnPin, R.drawable.ic_pinned, "Pin", GROUP_NOTEBOOK),
        ButtonSpec("lock", R.id.btnLock, R.drawable.ic_lock, "Encrypt", GROUP_NOTEBOOK),
        ButtonSpec("lockOff", R.id.btnLockOff, R.drawable.ic_lock_off, "Decrypt", GROUP_NOTEBOOK),
        ButtonSpec("pen", R.id.btnPen, R.drawable.ic_pen, "Pen", GROUP_TOOLS),
        ButtonSpec("eraser", R.id.btnEraser, R.drawable.ic_eraser, "Eraser", GROUP_TOOLS),
        ButtonSpec("lassoEraser", R.id.btnLassoEraser, R.drawable.ic_lasso_eraser, "Lasso Eraser", GROUP_TOOLS),
        ButtonSpec("eraseAll", R.id.btnEraseAll, R.drawable.ic_erase_all, "Erase All", GROUP_TOOLS),
        ButtonSpec("insertText", R.id.btnInsertText, R.drawable.ic_text_recognition, "Insert Text", GROUP_TOOLS),
        ButtonSpec("insertLines", R.id.btnInsertLines, R.drawable.ic_density_small, "Insert Lines", GROUP_TOOLS),
        ButtonSpec("lasso", R.id.btnLasso, R.drawable.ic_lasso, "Lasso", GROUP_TOOLS),
        ButtonSpec("undo", R.id.btnUndo, R.drawable.ic_undo, "Undo", GROUP_HISTORY),
        ButtonSpec("redo", R.id.btnRedo, R.drawable.ic_redo, "Redo", GROUP_HISTORY),
        ButtonSpec("template", R.id.btnTemplate, R.drawable.ic_template, "Template", GROUP_PAGE_VIEW),
        ButtonSpec("pageIndex", R.id.btnPageIndex, R.drawable.ic_files, "Page Index", GROUP_PAGE_VIEW),
        ButtonSpec("insertPageBefore", R.id.btnInsertPageBefore, R.drawable.ic_insert_page_before, "Insert Page Before", GROUP_PAGE_EDIT),
        ButtonSpec("insertPageAfter", R.id.btnInsertPageAfter, R.drawable.ic_insert_page_after, "Insert Page After", GROUP_PAGE_EDIT),
        ButtonSpec("deletePage", R.id.btnDeletePage, R.drawable.ic_page_delete, "Delete Page", GROUP_PAGE_EDIT),
        ButtonSpec("copyPage", R.id.btnCopyPage, R.drawable.ic_copy_page, "Copy Page", GROUP_PAGE_EDIT),
        ButtonSpec("pastePage", R.id.btnPastePage, R.drawable.ic_paste_page, "Paste Page", GROUP_PAGE_EDIT),
        ButtonSpec("toolbarSettings", R.id.btnToolbarSettings, R.drawable.ic_adjustments, "Customize Toolbar", GROUP_SETTINGS, pinned = true),
        ButtonSpec("scratchpad", R.id.btnScratchpad, R.drawable.ic_sketching, "Scratch Pad", GROUP_NOTEBOOK),
        ButtonSpec("stickyNote", R.id.btnInsertStickyNote, R.drawable.ic_sticker_2, "Insert Sticky Note", GROUP_TOOLS),
        ButtonSpec("insertShape", R.id.btnInsertShape, R.drawable.ic_convert_shape, "Insert Shape", GROUP_TOOLS),
        ButtonSpec("calendar", R.id.btnCalendar, R.drawable.ic_calendar, "Calendar", GROUP_NOTEBOOK),
    )

    private val byKey: Map<String, ButtonSpec> = SPECS.associateBy { it.key }

    fun spec(key: String): ButtonSpec? = byKey[key]

    /** Full default button order (current XML order, no spacer). */
    val DEFAULT_ORDER: List<String> = SPECS.map { it.key }

    /** Default mini set — a compact everyday subset. */
    val DEFAULT_MINI: List<String> = listOf("pen", "eraser", "undo", "lasso", "pageIndex")
}
