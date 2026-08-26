package com.symmetricalpalmtree.notesproutsn.templates

import com.symmetricalpalmtree.notesproutsn.data.index.ListIds
import com.symmetricalpalmtree.notesproutsn.data.index.ObjectSummary
import com.symmetricalpalmtree.notesproutsn.data.template.TemplateKind

/**
 * What a card on the Templates screen stands for (arc 13).
 *
 * There are **two kinds and no third**: a *built-in* is paper the app draws from arithmetic, a
 * *static template* is pixels the library keeps. Everything else here is scaffolding around those
 * two — [Blank] is the absence of paper, [Defaults] is the one reserved folder, [Folder] is a place.
 *
 * [Blank], [Defaults] and [BuiltIn] carry **sentinel ids** and no index row: they are hardcoded,
 * so nothing is seeded at bootstrap, nothing can be deleted or renamed, and an index restored from
 * a backup needs no repair. Only [Folder] and [Static] are rows, and only they long-press into the
 * management sheet.
 */
sealed class TemplateCard(val id: String, val name: String) {

    /** The card the whole screen is measured against: no template row at all, `refId` = `""`. */
    class Blank(name: String) : TemplateCard(ListIds.TEMPLATE_BLANK_ID, name)

    /**
     * The reserved **Default** folder holding the three built-in papers. Enterable, never editable:
     * it cannot be renamed, moved or deleted, and it is always there — a default set of templates
     * every notebook can reach, on any device, however empty the rest of the library is.
     */
    class Defaults(name: String) : TemplateCard(ListIds.TEMPLATE_DEFAULT_ID, name)

    /** Lined / Dotted / Grid — the app's own paper, drawn from arithmetic at the page's size. */
    class BuiltIn(id: String, name: String, val kind: TemplateKind) : TemplateCard(id, name)

    /** A user folder in the template library. */
    class Folder(val summary: ObjectSummary) : TemplateCard(summary.id, summary.name)

    /** A stored static template — an imported image (G4). Baked: what was imported is what it is. */
    class Static(val summary: ObjectSummary) : TemplateCard(summary.id, summary.name) {

        /** The base kind to draw a miniature from, or null when these are imported pixels. */
        val baseKind: TemplateKind? =
            TemplateKind.entries.firstOrNull { it.name == summary.templateKind }

        val isImage: Boolean get() = summary.templateKind == TemplateLibrary.KIND_IMAGE
    }

    /** True for the three hardcoded card families that are neither a place nor a row the user owns. */
    val isSentinel: Boolean get() = TemplateLibrary.isSentinel(id)

    /** The clock a thumbnail cache keys on. Sentinels never change, so they answer 0. */
    val stamp: Long
        get() = when (this) {
            is Folder -> summary.updatedAt
            is Static -> summary.updatedAt
            else -> 0L
        }
}
