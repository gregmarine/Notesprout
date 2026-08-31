package com.symmetricalpalmtree.notesproutsn.ext.document

/**
 * Marks one flagged word in the editor's buffer (arc 19 / M10).
 *
 * It carries **no styling of its own** — [ProofreadEditText] draws the dashed underline in its own
 * `onDraw`, where a [android.graphics.DashPathEffect] is possible and a `CharacterStyle` is not —
 * but as an `Editable` span it rides every edit, so a flag stays on its word while text moves around
 * it. That is the whole of why these are spans rather than a list of offsets kept beside the buffer.
 */
class ProofreadFlagSpan

/**
 * Marks one grammar finding — the dotted-underline sibling of [ProofreadFlagSpan].
 *
 * Unlike a spelling flag, whose word is looked up fresh at tap time, a grammar finding's message and
 * fix were computed against the text as it stood, so the span carries them — plus the [snippet] it
 * flagged: a tap on a span whose text has drifted mid-debounce is declined, and the imminent
 * re-check re-flags whatever still deserves it.
 */
class GrammarFlagSpan(
    val rule: String,
    val message: String,
    val replacement: String?,
    val snippet: String,
)
