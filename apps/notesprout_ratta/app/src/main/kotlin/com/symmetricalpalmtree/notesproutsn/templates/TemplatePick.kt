package com.symmetricalpalmtree.notesproutsn.templates

import com.symmetricalpalmtree.notesproutsn.data.index.ListIds
import com.symmetricalpalmtree.notesproutsn.data.template.TemplateKind

/**
 * What a tap on a template card *means* — the whole result contract between the browser and its
 * three hosts (arc 13 / G3), and deliberately the smallest thing that can carry it.
 *
 * It names a **card**, not paper. Resolving it into pixels is the host's job and needs the index
 * (a static template's bytes live in a row), so a pick that crosses an Activity boundary stays a
 * short string and the reader does the read — never a blob in an Intent extra.
 *
 * Three cases, because there are two card kinds and the absence of one:
 *  - [Blank] — no paper at all.
 *  - [BuiltIn] — Lined / Dotted / Grid, the app's own arithmetic.
 *  - [Static] — a library row, by id.
 */
sealed class TemplatePick {

    object Blank : TemplatePick()

    data class BuiltIn(val kind: TemplateKind) : TemplatePick()

    data class Static(val id: String) : TemplatePick()

    /**
     * The id of the card this pick stands for — a sentinel for the first two, the row's own id for
     * the third. It is what a host ticks while the user is still choosing (New Notebook), before
     * anything has been rendered and there is no token to compare.
     */
    val cardId: String
        get() = when (this) {
            Blank -> ListIds.TEMPLATE_BLANK_ID
            is BuiltIn -> TemplateLibrary.BUILT_IN_KINDS.firstOrNull { it.second == kind }?.first
                ?: ListIds.TEMPLATE_BLANK_ID
            is Static -> id
        }

    /** The wire form: one line, no separator a UUID or a kind name can contain. */
    fun encode(): String = when (this) {
        Blank -> BLANK
        is BuiltIn -> KIND_PREFIX + kind.name
        is Static -> STATIC_PREFIX + id
    }

    companion object {
        private const val BLANK = "blank"
        private const val KIND_PREFIX = "kind:"
        private const val STATIC_PREFIX = "static:"

        /**
         * The pick [encoded] stands for, or **null** when it is absent, empty, or names something
         * this build cannot make sense of. Null is the same answer as "the user backed out": a
         * caller that cannot read the result must change nothing, never guess Blank and wipe the
         * paper the page already had.
         */
        fun decode(encoded: String?): TemplatePick? = when {
            encoded.isNullOrEmpty() -> null
            encoded == BLANK -> Blank
            encoded.startsWith(KIND_PREFIX) -> encoded.removePrefix(KIND_PREFIX)
                .let { name -> TemplateKind.entries.firstOrNull { it.name == name } }
                ?.let { if (it == TemplateKind.BLANK) Blank else BuiltIn(it) }
            encoded.startsWith(STATIC_PREFIX) ->
                encoded.removePrefix(STATIC_PREFIX).takeIf { it.isNotEmpty() }?.let { Static(it) }
            else -> null
        }

        /** The pick a card stands for, or null for a place (a folder is entered, never picked). */
        fun of(card: TemplateCard): TemplatePick? = when (card) {
            is TemplateCard.Blank -> Blank
            is TemplateCard.BuiltIn -> BuiltIn(card.kind)
            is TemplateCard.Static -> Static(card.id)
            is TemplateCard.Folder, is TemplateCard.Defaults -> null
        }
    }
}
