package com.symmetricalpalmtree.notesprout.extension

/**
 * The host-process relay between a pick showing and the host's own New-notebook screen (arc 7 /
 * L3) — what keeps `ACTION_LINK_NEW_NOTEBOOK_SCREEN`'s Intent data-free in both directions:
 * `LinkCatalogBinder.prepareNewNotebook` parks the picker's browsed folder + the naming-scheme
 * default resolved for it; `NewNotebookActivity` (relay mode) reads them, creates without opening
 * and parks the created (id, name) back; `LinkCatalogBinder.takeCreatedNotebook` drains it.
 *
 * One slot — there is at most one pick showing at a time — cleared by the showing's revoke
 * (`LinkCatalogBinder.revoke`) so nothing stale ever leaks into a later showing. [prepared] does
 * NOT clear on read: the screen may be recreated (a Ratta keyboard attach) and must still find its
 * folder. Pure Kotlin, JVM-tested.
 */
object LinkCreateRelay {

    data class Prepared(val parentFolderId: String?, val defaultName: String?)
    data class Created(val id: String, val name: String)

    @Volatile
    private var preparedSlot: Prepared? = null

    @Volatile
    private var createdSlot: Created? = null

    /** Arm the screen: a new prepare drops any created notebook a previous arming left behind. */
    fun prepare(parentFolderId: String?, defaultName: String?) {
        preparedSlot = Prepared(parentFolderId, defaultName)
        createdSlot = null
    }

    /** The screen's read — kept until the next [prepare] or [clear] (recreation must re-find it). */
    fun prepared(): Prepared? = preparedSlot

    /** The screen's answer: the created notebook's identity for [takeCreated] to drain. */
    fun setCreated(id: String, name: String) {
        createdSlot = Created(id, name)
    }

    /** Read-and-clear what the screen created; null = cancelled (or nothing was armed). */
    fun takeCreated(): Created? = createdSlot.also { createdSlot = null }

    /** The showing is over (revoke) — nothing survives into the next one. */
    fun clear() {
        preparedSlot = null
        createdSlot = null
    }
}
