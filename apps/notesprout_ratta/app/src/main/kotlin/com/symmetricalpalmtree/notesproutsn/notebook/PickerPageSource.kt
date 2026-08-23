package com.symmetricalpalmtree.notesproutsn.notebook

/**
 * What the link picker asks a notebook for (arc 6 / K2): its page list, and one page's drawable +
 * labelable content. Two implementations: the current notebook answers from the **live session**
 * (armed through [LinkPickerRelay] — its `.soil` is already open and must never be opened twice),
 * and a browsed foreign notebook answers from its own read-only open ([ForeignPageSource]).
 */
interface PickerPageSource {
    suspend fun pages(): List<PickerPage>
    suspend fun content(pageId: String): PageContent?
}

/**
 * The process-local hand-off between the notebook screen and [LinkPickerActivity] — the family's
 * transfer-singleton shape (Paper's `LinkCreateRelay` precedent): the picker needs the live
 * session's data and **nothing rides the Intent** but the edit prefill (target ids only — never a
 * key, never content).
 *
 * Armed by [LinkPickFlow] immediately before the launch, read by the picker in `onCreate`
 * (null → the process was rebuilt while the picker was up: finish canceled, nothing to show),
 * cleared by the flow's result callback and by the notebook screen's close. The [Showing.source]
 * closes over the open [NotebookSession], which outlives the picker (the notebook screen stays
 * alive underneath) — the relay must never outlive it, which is what the two clear sites ensure.
 */
object LinkPickerRelay {

    class Showing(
        val notebookId: String,
        /** The page the link is being created on — excluded from the This-notebook grid. */
        val currentPageId: String,
        /** The current notebook's pages, served by the live session. */
        val source: PickerPageSource,
    )

    @Volatile
    var showing: Showing? = null
}
