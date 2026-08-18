package com.symmetricalpalmtree.notesprout.extension

/**
 * Inward caps for a provider's `describeEdit()` (arc 4 / H2 — pure, JVM-tested): title trimmed +
 * truncated to [ExtensionContract.MAX_EDIT_TITLE_CHARS], hint truncated to
 * [ExtensionContract.MAX_EDIT_HINT_CHARS], `maxChars` clamped to `1..MAX_EDIT_TEXT_CHARS`, the
 * prefill text truncated to that `maxChars`. (A blank title / out-of-range `maxChars` never reaches
 * here — [EditSpec]'s own `require`s reject them at unmarshal, which the client reports as a failure.)
 */
object EditCaps {
    fun sanitize(spec: EditSpec): EditSpec {
        val maxChars = spec.maxChars.coerceIn(1, ExtensionContract.MAX_EDIT_TEXT_CHARS)
        return EditSpec(
            title = spec.title.trim().take(ExtensionContract.MAX_EDIT_TITLE_CHARS),
            text = spec.text.take(maxChars),
            hint = spec.hint.trim().take(ExtensionContract.MAX_EDIT_HINT_CHARS),
            maxChars = maxChars,
            multiLine = spec.multiLine,
        )
    }
}
