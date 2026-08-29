package com.symmetricalpalmtree.notesproutsn.importing

/**
 * Which installed importer gets the document the user picked (arc 16 / I1) — pure, so the rule is
 * JVM-tested rather than device-tested.
 *
 * The match is on the **file extension of the picker's display name**, not on the MIME type: a
 * `.soil` has no registered type and providers hand it whatever they feel like (og's reason for
 * putting the wildcard type in the filter in the first place), so a MIME match would drop the one
 * importer that can actually read the file. The declared MIME list is used for the picker's filter
 * only —
 * [mimeFilter] — where a wrong guess costs a greyed-out file rather than a failed import.
 *
 * The collapse rule is arc 15's, kept: exactly one match is no question at all and no chooser is
 * shown; several is a chooser; none is a problem dialog, because an importer picked at random would
 * stream a document into a `.soil` probe that was always going to fail.
 */
object ImporterMatch {

    /** The lower-cased extension of [displayName], without the dot; `""` when it has none. A
     *  trailing dot, a leading dot (a dotfile) and a dot inside a directory-ish name all answer
     *  `""` rather than something surprising. */
    fun extensionOf(displayName: String): String {
        val name = displayName.substringAfterLast('/').substringAfterLast('\\')
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.length - 1) return ""
        return name.substring(dot + 1).lowercase()
    }

    /**
     * The indices of [declared] (one importer's accepted extensions per entry) that accept
     * [displayName], in the order they were given — which is the registry's (label, package) order,
     * so a chooser lists them the way every other list in the app is ordered.
     */
    fun matching(declared: List<List<String>>, displayName: String): List<Int> {
        val ext = extensionOf(displayName)
        if (ext.isEmpty()) return emptyList()
        return declared.indices.filter { i -> declared[i].any { it.equals(ext, ignoreCase = true) } }
    }

    /**
     * What the `OPEN_DOCUMENT` picker filters on: every MIME every installed importer declared,
     * **plus the wildcard type [ANY_TYPE]** — og's rule, and the reason is not laziness: providers
     * mislabel a `.soil` routinely, and a filter that hid the file the user came to import would be
     * a dead end with no explanation. Duplicates are dropped, order preserved.
     */
    fun mimeFilter(declared: List<List<String>>): Array<String> {
        val seen = LinkedHashSet<String>()
        for (list in declared) seen.addAll(list)
        seen.add(ANY_TYPE)
        return seen.toTypedArray()
    }

    const val ANY_TYPE = "*/*"
}
