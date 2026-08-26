package com.symmetricalpalmtree.notesproutsn.templates

import com.symmetricalpalmtree.notesproutsn.data.index.ListIds
import com.symmetricalpalmtree.notesproutsn.data.index.ObjectSummary
import com.symmetricalpalmtree.notesproutsn.data.index.ObjectType
import com.symmetricalpalmtree.notesproutsn.data.template.TemplateKind

/**
 * The template library's rules — **pure Kotlin, no Android, JVM-tested** (arc 13 / G1). Everything
 * the Templates screen decides *before* it draws anything lives here: what the root is made of,
 * what may be called what, and what a duplicate is named.
 *
 * The one structural idea it encodes: **the sentinels are not rows.** Blank, the Generated folder
 * and the three generators are hardcoded ids ([ListIds]) composed into every listing on the fly.
 * Nothing is seeded at bootstrap, nothing can be deleted or renamed, an index restored from a
 * backup needs no repair, and there is no migration. The database is asked only about the things
 * the user actually made.
 *
 * Labels come in as parameters rather than being read from resources, which is what keeps the
 * composition testable: the strings are the screen's business, the *order* is this file's.
 */
object TemplateLibrary {

    /** `templateKind` for a static row holding imported pixels rather than one of the four kinds. */
    const val KIND_IMAGE = "IMAGE"

    /**
     * The reserved folder name at the templates root. Compared **case-insensitively**: two cards a
     * user cannot tell apart are not two names, whatever SQLite thinks of the bytes.
     */
    const val RESERVED_ROOT_NAME = "Generated"

    /** The three generators, in the order they always appear inside Generated. */
    val GENERATOR_KINDS: List<Pair<String, TemplateKind>> = listOf(
        ListIds.TEMPLATE_LINED_ID to TemplateKind.LINED,
        ListIds.TEMPLATE_DOTTED_ID to TemplateKind.DOTTED,
        ListIds.TEMPLATE_GRID_ID to TemplateKind.GRID,
    )

    /** Every hardcoded id. Nothing here is ever written to, moved, renamed or deleted. */
    val SENTINEL_IDS: Set<String> = setOf(
        ListIds.TEMPLATE_BLANK_ID,
        ListIds.TEMPLATE_GENERATED_ID,
        ListIds.TEMPLATE_LINED_ID,
        ListIds.TEMPLATE_DOTTED_ID,
        ListIds.TEMPLATE_GRID_ID,
    )

    fun isSentinel(id: String): Boolean = id in SENTINEL_IDS

    /**
     * True when [name] may not be used under [parentId]. Only the **templates root** reserves
     * anything, and only the one name: deeper folders are the user's, and a folder called
     * "Generated" three levels down is a perfectly ordinary folder.
     *
     * It is reserved for templates as well as folders. The rule is about the *name at the root* —
     * a second card reading "Generated" beside the real one would be a confusion the user cannot
     * resolve by looking, whichever kind it is.
     */
    fun isReservedName(parentId: String?, name: String): Boolean =
        parentId == null && name.trim().equals(RESERVED_ROOT_NAME, ignoreCase = true)

    /**
     * The templates root: **Blank**, then **Generated**, then whatever the user made — already
     * sorted by the caller, folders first. The two synthetic cards always lead and are never
     * re-ordered by the sort control: they are the fixed furniture of the screen, and a sort that
     * moved Blank to the end would hide the most-used card behind a page turn.
     */
    fun rootCards(
        blankLabel: String,
        generatedLabel: String,
        sortedRows: List<ObjectSummary>,
    ): List<TemplateCard> = buildList {
        add(TemplateCard.Blank(blankLabel))
        add(TemplateCard.Generated(generatedLabel))
        addAll(rowCards(sortedRows))
    }

    /**
     * Inside **Generated**: the three generators and nothing else, ever. No row can land here (the
     * name is reserved at the root and the folder has no id in the database to be a `parentId`), so
     * this listing takes no arguments beyond its labels.
     */
    fun generatedCards(linedLabel: String, dottedLabel: String, gridLabel: String): List<TemplateCard> {
        val labels = listOf(linedLabel, dottedLabel, gridLabel)
        return GENERATOR_KINDS.mapIndexed { i, (id, kind) -> TemplateCard.Generator(id, labels[i], kind) }
    }

    /** Any ordinary folder: just the rows, already sorted. */
    fun rowCards(sortedRows: List<ObjectSummary>): List<TemplateCard> = sortedRows.map { row ->
        if (row.type == ObjectType.TEMPLATE_FOLDER) {
            TemplateCard.Folder(row)
        } else {
            TemplateCard.Static(row)
        }
    }

    /**
     * The name a duplicate gets: `"Ruled"` → `"Ruled copy"` → `"Ruled copy 2"` → … , skipping every
     * name already [taken] among its siblings.
     *
     * Matching is **exact**, because the database's own duplicate check is
     * exact too (`countSiblingsNamed` compares with `=`, and SQLite's default TEXT collation is
     * case-sensitive). A suffixing rule fussier than the constraint it is dodging would keep
     * looking for a collision the insert would have allowed.
     *
     * Bounded: after [MAX_DUPLICATE_TRIES] attempts it returns the last candidate and lets the
     * caller's duplicate check refuse it, rather than spinning on a pathological folder.
     */
    fun duplicateName(base: String, taken: Set<String>): String {
        val first = "$base $COPY_SUFFIX"
        if (first !in taken) return first
        var n = 2
        var candidate = "$first $n"
        while (candidate in taken && n < MAX_DUPLICATE_TRIES) {
            n++
            candidate = "$first $n"
        }
        return candidate
    }

    private const val COPY_SUFFIX = "copy"
    private const val MAX_DUPLICATE_TRIES = 1000
}
