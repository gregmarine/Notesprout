package com.symmetricalpalmtree.notesproutsn.importing

import com.symmetricalpalmtree.notesproutsn.extension.DocumentContract
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import kotlin.text.Charsets

/**
 * The host's half of a text import (arc 19 / M8) — what turns the bytes a
 * `RESULT_TEXT_DOCUMENT` importer delivered into document text, or refuses them. Pure and
 * JVM-tested: the extension streamed the picked file verbatim (its bytes are as untrusted as a
 * `.soil`'s), and this is the probe that stands where `NotebookImport.readManifest` stands for
 * a notebook.
 *
 * The rules, in order:
 *
 * 1. **Byte cap first** ([MAX_TEXT_BYTES], checked by the caller against the landed file before
 *    the bytes are even read — a first-hand `File.length()` count, the arc-16 corroboration
 *    discipline).
 * 2. **Strict UTF-8** — `CodingErrorAction.REPORT`, never the stdlib's lossy
 *    `String(bytes)`: a malformed sequence refuses the import instead of silently landing
 *    mojibake in a document the user would then edit ([Refusal.NOT_TEXT]).
 * 3. **No NULs** — a decodable file holding `U+0000` is binary wearing a text extension
 *    ([Refusal.NOT_TEXT]).
 * 4. **Char cap** ([DocumentContract.MAX_DOCUMENT_CHARS], re-checked after decode — the byte cap
 *    alone does not bound chars: 10 MB of ASCII is exactly 10 M chars, and the normalizations
 *    below only shrink, but the contract cap is the one the editor enforces, so it is the one
 *    that must hold — [Refusal.TOO_LONG]).
 *
 * What survives is normalized, not rewritten: a leading BOM (`U+FEFF`) is dropped, and line
 * endings become `\n` (`\r\n` and lone `\r` — the markdown engine and every draft rule in
 * `:markdown` speak `\n` only). Nothing else is touched — the text is the user's.
 */
object TextImport {

    /** The delivery cap — 10 MB, deliberately aligned with [DocumentContract.MAX_DOCUMENT_CHARS]
     *  (UTF-8 chars ≤ bytes, so nothing under this cap is unconditionally over the char cap). */
    const val MAX_TEXT_BYTES: Long = 10_000_000L

    /** Why the bytes were refused — the caller maps these onto its problem dialog. */
    enum class Refusal { NOT_TEXT, TOO_LONG }

    /** A refused text delivery; [refusal] picks the dialog body. */
    class TextProblem(val refusal: Refusal, cause: Throwable? = null) : Exception(cause)

    /**
     * Decode, validate and normalize a delivered text file's bytes. Returns the document text
     * ready for the notebook-document row; throws [TextProblem] and nothing else for content
     * refusals. The [MAX_TEXT_BYTES] check belongs to the caller (it has the landed file's
     * length first-hand); this method still refuses a byte array over the cap so the rule
     * cannot be skipped.
     */
    fun decode(bytes: ByteArray): String {
        if (bytes.size > MAX_TEXT_BYTES) throw TextProblem(Refusal.TOO_LONG)
        val decoded =
            try {
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString()
            } catch (e: CharacterCodingException) {
                throw TextProblem(Refusal.NOT_TEXT, e)
            }
        if ('\u0000' in decoded) throw TextProblem(Refusal.NOT_TEXT)
        val text = normalize(decoded)
        if (text.length > DocumentContract.MAX_DOCUMENT_CHARS) throw TextProblem(Refusal.TOO_LONG)
        return text
    }

    /** Drop a leading BOM; fold `\r\n` and lone `\r` to `\n`. Shrinks or keeps — never grows. */
    fun normalize(text: String): String =
        text.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
}
