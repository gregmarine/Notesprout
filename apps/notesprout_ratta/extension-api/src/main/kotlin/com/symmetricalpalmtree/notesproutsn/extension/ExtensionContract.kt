package com.symmetricalpalmtree.notesproutsn.extension

/**
 * The stable contract shared by the Notesprout SN core (host) and a recognizer extension. This
 * library depends on nothing in `:app` and on no library beyond the Kotlin stdlib.
 *
 * SN's contract is deliberately **one point wide**: [ACTION_HANDWRITING_RECOGNIZER]
 * (`IHandwritingRecognizer`) exists solely so other HWR engines can slot in later; headings and the
 * markdown engine are core (the arc-3 amendment to the arc-1 "no extensions" rule). The action
 * string is SN-namespaced — a Paper extension on the same device never matches it, and each
 * family's `HostCallerCheck` refuses the other family's host.
 */
object ExtensionContract {

    /** Current API version. An extension is used only if its `<service>` meta-data equals this. */
    const val API_VERSION: Int = 1

    /** Intent action a handwriting-recognizer `<service>` declares in its intent-filter. */
    const val ACTION_HANDWRITING_RECOGNIZER: String =
        "com.symmetricalpalmtree.notesproutsn.extension.HANDWRITING_RECOGNIZER"

    /** `<meta-data>` name (on the `<service>`) carrying the extension's API version. */
    const val META_API_VERSION: String =
        "com.symmetricalpalmtree.notesproutsn.extension.API_VERSION"

    // ── Handwriting-recognizer caps ──────
    // Enforced by the host BEFORE the call (no bind over the cap) and re-checked by the extension.

    /** Most strokes in one `recognizeInk` / `recognizePage` call. */
    const val MAX_INK_STROKES: Int = 2_000

    /** Most points (summed over all strokes) in one recognize call (≈ 480 KB of floats). */
    const val MAX_INK_POINTS: Int = 60_000

    /** The host truncates `preContext` to its last this-many chars before the call. */
    const val MAX_PRECONTEXT_CHARS: Int = 20

    /** Host-side cap on the text a recognize call returns (chars); the rest is dropped. */
    const val MAX_RECOGNIZED_CHARS: Int = 20_000

    /**
     * The exact message of the `IllegalStateException` a recognizer throws from `recognize*` when it
     * could not become READY within the call (still acquiring its model, or nothing acquired yet).
     * The host types that one case ("still downloading"); any other `IllegalStateException` is an
     * engine failure. Recognizers must use this constant — the host compares the message, not a
     * substring.
     */
    const val RECOGNIZER_NOT_READY: String = "recognizer not ready"
}
