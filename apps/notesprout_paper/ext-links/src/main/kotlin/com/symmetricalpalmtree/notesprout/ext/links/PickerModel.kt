package com.symmetricalpalmtree.notesprout.ext.links

import com.symmetricalpalmtree.notesprout.extension.ExtensionContract

/**
 * The link picker's decisions, with no Android in them (arc 7 / L2) — everything
 * [LinkPickerActivity] has to be *right* about, made JVM-testable: which mode a mode is, what an
 * Edit's payload pre-selects, which catalog rows a browse grid may show, how a foreign notebook's
 * unlabelled page is named, which create buttons a browse position offers (L3), and what OK composes
 * (or refuses to).
 *
 * The Activity keeps only the wiring: catalog calls, view inflation, dialogs. Nothing here talks to
 * the host, and nothing here knows a `CatalogEntry` — rows arrive mapped into [Entry] (the
 * `ScratchInk` precedent: the pure half never imports the parcelable).
 */
object PickerModel {

    /**
     * The three target modes, in the order the toggle shows them (L2 Q1):
     * a page of the link's own notebook · another notebook · a page of another notebook.
     */
    enum class Mode { THIS_NOTEBOOK, NOTEBOOK, NOTEBOOK_PAGE }

    /** The modes in toggle order — the single source for the three buttons and their tests. */
    val MODES: List<Mode> = listOf(Mode.THIS_NOTEBOOK, Mode.NOTEBOOK, Mode.NOTEBOOK_PAGE)

    /** One catalog row, stripped of the parcelable: `kind` is an `ExtensionContract.CATALOG_*`. */
    data class Entry(val id: String, val kind: Int, val label: String)

    /** What the screen starts in: mode + chrome + (for Edit) the row to show selected. */
    data class Prefill(
        val mode: Mode,
        val chrome: Int,
        /** The id to show selected wherever it appears in the grid; null = nothing selected. */
        val selectedId: String?,
        /** Non-null = open drilled into this notebook's pages instead of the folder browse. */
        val drillNotebookId: String?,
    )

    /** What OK will hand [LinkPayload.encode] — the picker never composes a payload by hand. */
    data class Composition(
        val chrome: Int,
        val kind: Int,
        val notebookId: String?,
        val pageId: String?,
    )

    /** The destination kind a mode produces (`ExtensionContract.DEST_*`). */
    fun kindOf(mode: Mode): Int = when (mode) {
        Mode.THIS_NOTEBOOK -> ExtensionContract.DEST_PAGE
        Mode.NOTEBOOK -> ExtensionContract.DEST_NOTEBOOK
        Mode.NOTEBOOK_PAGE -> ExtensionContract.DEST_NOTEBOOK_PAGE
    }

    /** The mode a stored destination kind reopens in; null for a kind we do not know. */
    fun modeOf(kind: Int): Mode? = when (kind) {
        ExtensionContract.DEST_PAGE -> Mode.THIS_NOTEBOOK
        ExtensionContract.DEST_NOTEBOOK -> Mode.NOTEBOOK
        ExtensionContract.DEST_NOTEBOOK_PAGE -> Mode.NOTEBOOK_PAGE
        else -> null
    }

    /** A create's starting point: "This notebook", underline chrome, nothing selected. */
    val CREATE_PREFILL: Prefill = Prefill(
        mode = Mode.THIS_NOTEBOOK,
        chrome = ExtensionContract.LINK_CHROME_UNDERLINE,
        selectedId = null,
        drillNotebookId = null,
    )

    /**
     * Where an Edit opens. A null [decoded] (create, or a payload this version cannot read) is
     * [CREATE_PREFILL]; otherwise the mode comes from the kind, the chrome from the payload, and the
     * target is pre-selected — a `DEST_NOTEBOOK_PAGE` opens **drilled straight into** its notebook's
     * pages. A pre-selected id that is no longer listed simply never shows as selected; the picker
     * does not complain about it.
     */
    fun prefill(decoded: LinkPayload.Decoded?): Prefill {
        val d = decoded ?: return CREATE_PREFILL
        return when (modeOf(d.kind)) {
            Mode.THIS_NOTEBOOK -> Prefill(Mode.THIS_NOTEBOOK, d.chrome, d.pageId, null)
            Mode.NOTEBOOK -> Prefill(Mode.NOTEBOOK, d.chrome, d.notebookId, null)
            Mode.NOTEBOOK_PAGE -> Prefill(Mode.NOTEBOOK_PAGE, d.chrome, d.pageId, d.notebookId)
            null -> CREATE_PREFILL
        }
    }

    /**
     * The folder-browse rows a grid may show: the catalog's own order, minus **the notebook the link
     * lives in** — linking a notebook to itself is a no-op trap, so it is hidden in both browse modes
     * (L2 build note). A blank [currentNotebookId] hides nothing.
     */
    fun browseEntries(entries: List<Entry>, currentNotebookId: String?): List<Entry> {
        if (currentNotebookId.isNullOrBlank()) return entries
        return entries.filter { it.kind != ExtensionContract.CATALOG_NOTEBOOK || it.id != currentNotebookId }
    }

    /**
     * A page card's text. The **current** notebook's pages arrive fully composed by the host
     * ("Page 3", "Page 3 — Heading") and are shown verbatim; a foreign notebook's pages arrive blank
     * and are named from their 1-based position through [fallback] (the `links_page_n` resource).
     */
    fun pageLabel(entry: Entry, position: Int, fallback: (Int) -> String): String =
        entry.label.ifBlank { fallback(position) }

    /**
     * What OK composes, or **null when the screen has nothing to link to yet** — no selection, or a
     * "Notebook page" pick with no notebook drilled into. Null is the honest "choose a target first"
     * case; the picker never disables its OK button (a disabled control is invisible on e-ink).
     */
    fun compose(
        mode: Mode,
        chrome: Int,
        selectedId: String?,
        drillNotebookId: String?,
    ): Composition? {
        val selected = selectedId?.takeIf { it.isNotBlank() } ?: return null
        return when (mode) {
            Mode.THIS_NOTEBOOK -> Composition(chrome, ExtensionContract.DEST_PAGE, null, selected)
            Mode.NOTEBOOK -> Composition(chrome, ExtensionContract.DEST_NOTEBOOK, selected, null)
            Mode.NOTEBOOK_PAGE -> {
                val notebook = drillNotebookId?.takeIf { it.isNotBlank() } ?: return null
                Composition(chrome, ExtensionContract.DEST_NOTEBOOK_PAGE, notebook, selected)
            }
        }
    }

    /**
     * Switching modes throws the current target away and returns to the top of the browse — the two
     * browse modes select different *kinds* of thing, so a carried-over selection would be a lie.
     * (The initial prefill is applied instead of a switch, so it survives.)
     */
    fun afterModeSwitch(mode: Mode, chrome: Int): Prefill =
        Prefill(mode = mode, chrome = chrome, selectedId = null, drillNotebookId = null)

    /** Which of the top bar's three create buttons this browse position shows (arc 7 / L3). */
    data class CreateButtons(val newPage: Boolean, val newFolder: Boolean, val newNotebook: Boolean)

    /**
     * What the user may create from where they are standing (L3). "This notebook" only ever shows a
     * page in the notebook the link lives in; the two browse modes show the library's own pair
     * (folder + notebook) — and "Notebook page", once it has drilled into a notebook, is standing in
     * a page grid, so it offers a page instead.
     *
     * The buttons are **absent**, never disabled — a disabled control is invisible on e-ink. The one
     * extra hide the screen adds on top of this is Activity wiring, not a decision: a showing with no
     * current notebook has no notebook to add a page to.
     */
    fun createButtons(mode: Mode, drilled: Boolean): CreateButtons = when (mode) {
        Mode.THIS_NOTEBOOK -> CreateButtons(newPage = true, newFolder = false, newNotebook = false)
        Mode.NOTEBOOK -> CreateButtons(newPage = false, newFolder = true, newNotebook = true)
        Mode.NOTEBOOK_PAGE ->
            if (drilled) CreateButtons(newPage = true, newFolder = false, newNotebook = false)
            else CreateButtons(newPage = false, newFolder = true, newNotebook = true)
    }

    /** A `pathTo` reply split for the browse state: the folder stack to seed + the notebook's name. */
    data class Path(val folders: List<Pair<String, String>>, val notebookId: String, val notebookName: String)

    /**
     * Validate + split a `pathTo` reply (L2 fix — an Edit prefill opens the browse where its target
     * notebook lives). Well-formed = the notebook itself as the LAST entry (kind `CATALOG_NOTEBOOK`)
     * with only folders (kind `CATALOG_FOLDER`) before it, root-first. Anything else — empty, no
     * notebook tail, a stray kind — is null: prefill is cosmetic, so a bad reply means "start at the
     * root", never an error.
     */
    fun pathParts(entries: List<Entry>): Path? {
        val notebook = entries.lastOrNull() ?: return null
        if (notebook.kind != ExtensionContract.CATALOG_NOTEBOOK) return null
        val folders = entries.dropLast(1)
        if (folders.any { it.kind != ExtensionContract.CATALOG_FOLDER }) return null
        return Path(folders.map { it.id to it.label }, notebook.id, notebook.label)
    }
}
