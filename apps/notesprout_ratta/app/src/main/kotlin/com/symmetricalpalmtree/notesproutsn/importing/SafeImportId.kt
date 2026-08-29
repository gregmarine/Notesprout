package com.symmetricalpalmtree.notesproutsn.importing

/**
 * **The untrusted-manifest rule** (arc 16 / I1, og's `isSafeImportId`): every id read out of an
 * incoming `notebook_meta` — the notebook's own and every `folderPath` segment's — is validated
 * before it is used as a [com.symmetricalpalmtree.notesproutsn.data.soilFile] path component or as
 * an index primary key.
 *
 * The file is bytes a stranger wrote. An id like `../../notesprout` would name a path outside the
 * Garden the moment it reached `soilFile()`, and an id that is merely odd would still become a
 * primary key nothing else in the app can produce. So the alphabet is the **UUID alphabet only**:
 * canonical `8-4-4-4-12` hex, which is exactly what `UUID.randomUUID().toString()` writes and
 * exactly what every file in the family carries. Anything else is not repaired, not escaped, not
 * trusted — it is simply not used, and a notebook id that fails falls back to a fresh UUID (which
 * is what forces the in-file remap pass).
 *
 * Pure, so it is JVM-tested rather than argued about.
 */
object SafeImportId {

    /** Case-insensitive so a hand-written or foreign-cased id is still recognisable; nothing else
     *  is relaxed — no braces, no URN prefix, no missing dashes. */
    private val UUID_FORM = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

    fun isSafe(id: String?): Boolean = id != null && UUID_FORM.matches(id)

    /** [id] when it is safe to use, else null — the shape every caller wants. */
    fun orNull(id: String?): String? = if (isSafe(id)) id else null
}
