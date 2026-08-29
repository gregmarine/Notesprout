package com.symmetricalpalmtree.notesproutsn.importing

import com.symmetricalpalmtree.notesproutsn.data.soil.FolderRef

/**
 * **"Notebook's folders", planned before a single row is written** (arc 16 / I1) — the create-only
 * ancestry recreation, as a pure decision so the rule is JVM-tested rather than argued about at a
 * call site.
 *
 * The rule, and it has no exceptions: **an imported `folderPath` may create folders and may never
 * touch one.** A segment whose id the index does not know is created with that id and that name; a
 * segment whose id already exists — as a live folder, as a **soft-deleted** folder, as a notebook,
 * as anything at all — is never mutated, and the descent **stops one level up** (og's rule, with
 * the deleted-folder case tightened from og's un-delete on the arc-16 wizard's call: reviving a
 * folder the user threw away is a mutation, and this pass does not mutate).
 *
 * A live folder of the right kind is the one "already there" case that is *not* a stop: the descent
 * simply continues through it, which is what makes re-importing into an ancestry that already exists
 * a no-op rather than a duplicate.
 *
 * The walk is bounded ([MAX_DEPTH]) and validates every id ([SafeImportId]) — an unsafe id can
 * neither be created nor descended into, so it stops the descent like any other blocked segment.
 */
object AncestryPlan {

    /** How deep an incoming ancestry may reach. The library's own walk is capped at 50 hops; an
     *  imported path is a stranger's, so it gets a tighter one. */
    const val MAX_DEPTH = 20

    /** What the index already holds at one incoming folder id. */
    enum class Slot {
        /** Nothing has this id — it may be created. */
        MISSING,

        /** A live folder of the library's own hierarchy — descend through it, change nothing. */
        LIVE_FOLDER,

        /** Anything else: a soft-deleted folder, a notebook, a template, a list. Never mutated,
         *  and the descent ends here. */
        BLOCKED,
    }

    /** One folder to create, in the order it must be created (parents first). */
    data class Create(val id: String, val name: String, val parentId: String?)

    /**
     * [parentId] is where the notebook lands (null = the library root); [create] is what must be
     * written first, parents before children. [truncated] is true when a segment blocked the
     * descent — the notebook lands one level up, and nothing about that is an error.
     */
    data class Plan(val parentId: String?, val create: List<Create>, val truncated: Boolean)

    /**
     * Plan [path] (root-first, as `notebook_meta.folderPath` stores it) against the index, asked
     * one id at a time through [slotOf] — the caller's database read, kept outside so this stays
     * pure.
     */
    fun plan(path: List<FolderRef>, slotOf: (String) -> Slot): Plan {
        val create = ArrayList<Create>(path.size)
        var parent: String? = null
        var truncated = false
        for ((depth, ref) in path.withIndex()) {
            if (depth >= MAX_DEPTH) { truncated = true; break }
            val id = SafeImportId.orNull(ref.id)
            if (id == null) { truncated = true; break }
            when (slotOf(id)) {
                Slot.LIVE_FOLDER -> parent = id
                Slot.MISSING -> {
                    create += Create(id, ImportNames.folderName(ref.name), parent)
                    parent = id
                }
                Slot.BLOCKED -> { truncated = true; break }
            }
        }
        return Plan(parent, create, truncated)
    }
}
