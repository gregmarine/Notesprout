package com.symmetricalpalmtree.notesproutsn.library

/**
 * What a folder or notebook may be called. Family rule, unchanged from Paper: a name goes nowhere
 * near the filesystem (files are `<uuid>.soil`, structure lives in the index) but the whitelist is
 * kept anyway so a name is always safe to put in an export filename, a path, or a shell line.
 *
 * Uniqueness is *not* here — it is a database question (alive siblings of the same type under the
 * same parent) and lives in `IndexRepository.nameTaken`. Callers do both: [validate] first, then
 * the duplicate check.
 */
object NameRules {

    private val ALLOWED = Regex("^[a-zA-Z0-9_\\-. ]+$")

    /** The three ways a name can fail, as string-resource-free tokens the caller maps to text. */
    enum class Problem { EMPTY, RESERVED, CHARSET }

    /** Null when [name] (already trimmed by the caller) is acceptable. */
    fun validate(name: String): Problem? = when {
        name.isBlank() -> Problem.EMPTY
        name == "." || name == ".." -> Problem.RESERVED
        !ALLOWED.matches(name) -> Problem.CHARSET
        else -> null
    }

    fun isValid(name: String): Boolean = validate(name) == null
}
