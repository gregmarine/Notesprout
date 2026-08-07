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
        // "toc" (Table of Contents) retired from the toolbar — reachable via the canvas gesture, which
        // is the sole entry point now; spec removed. "export" (Export) moved to the canvas long-press
        // "Page" menu; spec removed. Persisted configs that still list these keys resolve to null and
        // are skipped harmlessly.
        // "textRecognition" (Text) moved to the canvas long-press "Page" menu — its two actions ("View
        // recognized text" + the Real-time-text toggle) live there now; spec removed. A persisted config
        // that still lists it resolves to null and is skipped harmlessly.
        // "pin" moved to the top of the canvas long-press "Page" menu; spec removed. A persisted config
        // that still lists it resolves to null and is skipped harmlessly.
        ButtonSpec("lock", R.id.btnLock, R.drawable.ic_lock, "Encrypt", GROUP_NOTEBOOK),
        // "lockOff" (Decrypt) retired under encrypt-everything — a notebook is never downgraded to
        // plaintext; scope is changed via the long-press "Change Encryption Scope" toggle instead.
        // Persisted configs that still list "lockOff" resolve to null and are skipped harmlessly.
        ButtonSpec("pen", R.id.btnPen, R.drawable.ic_pen_filled, "Pen", GROUP_TOOLS),
        ButtonSpec("eraser", R.id.btnEraser, R.drawable.ic_eraser, "Eraser", GROUP_TOOLS),
        ButtonSpec("lassoEraser", R.id.btnLassoEraser, R.drawable.ic_lasso_eraser, "Lasso Eraser", GROUP_TOOLS),
        // "eraseAll" (Erase Page) moved to the canvas long-press "Page" menu; spec removed. A
        // persisted config that still lists it resolves to null and is skipped harmlessly.
        // "insertText" (Insert Text) retired — strokes are converted to text instead; spec removed.
        // A persisted config that still lists it resolves to null and is skipped harmlessly.
        ButtonSpec("insertLines", R.id.btnInsertLines, R.drawable.ic_density_small, "Insert Lines", GROUP_TOOLS),
        ButtonSpec("lasso", R.id.btnLasso, R.drawable.ic_lasso, "Lasso", GROUP_TOOLS),
        ButtonSpec("undo", R.id.btnUndo, R.drawable.ic_undo, "Undo", GROUP_HISTORY),
        ButtonSpec("redo", R.id.btnRedo, R.drawable.ic_redo, "Redo", GROUP_HISTORY),
        // The page-view / page-edit buttons (template, pageIndex, insertPageBefore, insertPageAfter,
        // deletePage, copyPage, pastePage) moved to the canvas long-press "Page" menu; their specs are
        // removed. Persisted configs that still list these keys resolve to null and are skipped.
        ButtonSpec("toolbarSettings", R.id.btnToolbarSettings, R.drawable.ic_adjustments, "Customize Toolbar", GROUP_SETTINGS, pinned = true),
        ButtonSpec("scratchpad", R.id.btnScratchpad, R.drawable.ic_sketching, "Scratch Pad", GROUP_NOTEBOOK),
        ButtonSpec("stickyNote", R.id.btnInsertStickyNote, R.drawable.ic_sticker_2, "Insert Sticky Note", GROUP_TOOLS),
        ButtonSpec("insertShape", R.id.btnInsertShape, R.drawable.ic_convert_shape, "Insert Shape", GROUP_TOOLS),
        ButtonSpec("calendar", R.id.btnCalendar, R.drawable.ic_calendar, "Calendar", GROUP_NOTEBOOK),
        ButtonSpec("tasks", R.id.btnTasks, R.drawable.ic_tasks, "Tasks", GROUP_NOTEBOOK),
        ButtonSpec("document", R.id.btnDocument, R.drawable.ic_file_text, "Document", GROUP_NOTEBOOK),
    )

    private val byKey: Map<String, ButtonSpec> = SPECS.associateBy { it.key }

    fun spec(key: String): ButtonSpec? = byKey[key]

    /**
     * Full default button order (no spacer). **Bracketed layout:** navigation on the edges, content
     * tools centered under the hand. Left = enter/switch; center = ink & select → insert-objects →
     * history; right = auxiliary surfaces, then the (runtime-hidden-on-encrypted) Encrypt button and
     * the pinned Customize gear. Decoupled from [SPECS] declaration order on purpose — [SPECS] stays
     * append-only/stable per the KEY STABILITY RULE, while this list defines the *display* default.
     * Must contain every live [SPECS] key (else [ToolbarPreferencesManager.load] appends the missing
     * ones to the end).
     */
    val DEFAULT_ORDER: List<String> = listOf(
        // Navigation (left edge): exit + notebook switch.
        "close", "recents",
        // History — fixed spot right after navigation, the constantly-used actions while writing.
        "undo", "redo",
        // Content tools (center): ink & select …
        "pen", "eraser", "lassoEraser", "lasso",
        // … then insert-objects.
        "insertLines", "insertShape", "stickyNote",
        // Auxiliary surfaces (right edge).
        "scratchpad", "calendar", "tasks", "document",
        // Latent Encrypt (plaintext notebooks only; runtime-hidden otherwise) + pinned Customize gear.
        "lock",
        "toolbarSettings",
    )

    /** Default mini set — a compact everyday subset. */
    val DEFAULT_MINI: List<String> = listOf("pen", "eraser", "undo", "lasso")
}
