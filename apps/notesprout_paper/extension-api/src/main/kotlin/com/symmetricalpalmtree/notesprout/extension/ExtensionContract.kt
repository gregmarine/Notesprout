package com.symmetricalpalmtree.notesprout.extension

/**
 * The stable contract shared by the Notesprout Paper core (host) and every extension. This library
 * depends on nothing in `:app` and on no library beyond the Kotlin stdlib, so a third party can
 * consume it as a plain artifact.
 *
 * v1 has three extension points: [ACTION_TEMPLATE_PROVIDER] (interface `ITemplateProvider`),
 * [ACTION_NOTEBOOK_NAMER] (interface `INotebookNamer`, arc 2) and [ACTION_HANDWRITING_RECOGNIZER]
 * (interface `IHandwritingRecognizer`, arc 3 — a *capability* point: the host binds it and may lend
 * it to other extensions later). `IExtensionStore` (arc 2) is not a point — it is the host-owned
 * store handed *to* an extension as an in-parameter of a call; its caps are the `STORE_*` constants
 * below.
 */
object ExtensionContract {

    /** Current API version. An extension is used only if its `<service>` meta-data equals this. */
    const val API_VERSION: Int = 1

    /** Intent action a template-provider `<service>` declares in its intent-filter. */
    const val ACTION_TEMPLATE_PROVIDER: String =
        "com.symmetricalpalmtree.notesprout.extension.TEMPLATE_PROVIDER"

    /** Intent action a notebook-namer `<service>` declares in its intent-filter (arc 2). */
    const val ACTION_NOTEBOOK_NAMER: String =
        "com.symmetricalpalmtree.notesprout.extension.NOTEBOOK_NAMER"

    /** Intent action a handwriting-recognizer `<service>` declares in its intent-filter (arc 3). */
    const val ACTION_HANDWRITING_RECOGNIZER: String =
        "com.symmetricalpalmtree.notesprout.extension.HANDWRITING_RECOGNIZER"

    /** `<meta-data>` name (on the `<service>`) carrying the extension's API version. */
    const val META_API_VERSION: String =
        "com.symmetricalpalmtree.notesprout.extension.API_VERSION"

    /** MIME type of the bytes a [RenderedTemplate] carries. */
    const val MIME_WEBP: String = "image/webp"

    /** Hard cap the host enforces on a render result (16 MiB). */
    const val MAX_RENDER_BYTES: Int = 16 * 1024 * 1024

    // ── Extension store caps (`IExtensionStore`, enforced by the host) ──────

    /** Longest key an extension may store (chars); the empty key is rejected. */
    const val STORE_MAX_KEY_CHARS: Int = 512

    /** Largest value an extension may store (256 KiB). */
    const val STORE_MAX_VALUE_BYTES: Int = 256 * 1024

    /** Most keys one extension's store may hold. */
    const val STORE_MAX_KEYS: Int = 50_000

    /** Host-side cap on a notebook name / naming-scheme text an extension returns (chars). */
    const val MAX_NAME_CHARS: Int = 100

    // ── Handwriting-recognizer caps (`IHandwritingRecognizer`, arc 3) ──────
    // Enforced by the host BEFORE the call (no bind over the cap) and re-checked by the extension.

    /** Most strokes in one `recognizeInk` / `recognizePage` call. */
    const val MAX_INK_STROKES: Int = 2_000

    /** Most points (summed over all strokes) in one recognize call (≈ 480 KB of floats). */
    const val MAX_INK_POINTS: Int = 60_000

    /** The host truncates `preContext` to its last this-many chars before the call. */
    const val MAX_PRECONTEXT_CHARS: Int = 20

    /** Host-side cap on the text a recognize call returns (chars); the rest is dropped. */
    const val MAX_RECOGNIZED_CHARS: Int = 20_000

    /** Extension-namespaced template identity: `"<extension package>:<template id>"`. */
    fun templateIdentity(pkg: String, id: String): String = "$pkg:$id"

    /**
     * Split a template identity at the FIRST `:` into `(pkg, id)`. Returns null if there is no `:` or
     * either side is empty. The Blank sentinel is the host's concern, not the contract's.
     */
    fun parseIdentity(s: String): Pair<String, String>? {
        val i = s.indexOf(':')
        if (i <= 0 || i >= s.length - 1) return null
        return s.substring(0, i) to s.substring(i + 1)
    }
}
