package com.symmetricalpalmtree.notesprout.extension

/**
 * The stable contract shared by the Notesprout Paper core (host) and every extension. This library
 * depends on nothing in `:app` and on no library beyond the Kotlin stdlib, so a third party can
 * consume it as a plain artifact.
 *
 * v1 has five extension points: [ACTION_TEMPLATE_PROVIDER] (interface `ITemplateProvider`),
 * [ACTION_NOTEBOOK_NAMER] (interface `INotebookNamer`, arc 2), [ACTION_HANDWRITING_RECOGNIZER]
 * (interface `IHandwritingRecognizer`, arc 3 — a *capability* point: the host binds it and lends it
 * to other extensions through a proxy), [ACTION_MARKDOWN_RENDERER] (interface `IMarkdownRenderer`,
 * arc 4 — a second capability point: markdown in, image out) and [ACTION_OBJECT_PROVIDER]
 * (interface `IObjectProvider`, arc 4 — the generic content-object point; the two capabilities reach
 * a provider only as in-parameters of its calls). `IExtensionStore` (arc 2) is not a
 * point — it is the host-owned store handed *to* an extension as an in-parameter of a call; its caps
 * are the `STORE_*` constants below.
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

    /** Intent action a markdown-renderer `<service>` declares in its intent-filter (arc 4). */
    const val ACTION_MARKDOWN_RENDERER: String =
        "com.symmetricalpalmtree.notesprout.extension.MARKDOWN_RENDERER"

    /** Intent action an object-provider `<service>` declares in its intent-filter (arc 4 / H3). */
    const val ACTION_OBJECT_PROVIDER: String =
        "com.symmetricalpalmtree.notesprout.extension.OBJECT_PROVIDER"

    /** `<meta-data>` name (on the `<service>`) carrying the extension's API version. */
    const val META_API_VERSION: String =
        "com.symmetricalpalmtree.notesprout.extension.API_VERSION"

    /** MIME type of the bytes a [RenderedTemplate] carries. */
    const val MIME_WEBP: String = "image/webp"

    /** Hard cap the host enforces on a render result (16 MiB) — a [RenderedTemplate] or a [RenderedImage]. */
    const val MAX_RENDER_BYTES: Int = 16 * 1024 * 1024

    // ── Markdown-renderer caps (`IMarkdownRenderer`, arc 4) ──────
    // The host truncates / rejects BEFORE the call; the extension re-checks (`IllegalArgumentException`).

    /** Longest markdown source one `render` accepts (chars). */
    const val MAX_MARKDOWN_CHARS: Int = 20_000

    /** A [RenderedImage] may not exceed this on either side (px) — host and extension both enforce it. */
    const val MAX_IMAGE_EDGE_PX: Int = 4_096

    /** Most padding (px, all four sides) a markdown render may be asked for. */
    const val RENDER_PADDING_MAX_PX: Int = 64

    // ── Content objects (arc 4 / H1 — the host's `object` rows; `IObjectProvider` — arc 4 / H3) ──────

    /** Longest object payload the host stores in an `object` row / hands to a provider (chars). Opaque
     *  to the host: never parsed, never logged; truncated to this on the way in and on the way out. */
    const val MAX_OBJECT_TEXT_CHARS: Int = 20_000

    /** Longest object `typeId` (chars); typeIds are `[a-z0-9_-]+` (see [isTypeId]). */
    const val MAX_TYPE_ID_CHARS: Int = 32

    /** Most `describeTypes()` entries the host keeps from one provider. */
    const val MAX_TYPES: Int = 16

    /** Host cap on the number of live objects one page may hold (creation refused above it). */
    const val MAX_OBJECTS_PER_PAGE: Int = 200

    // ── Outline entries (arc 5 / C0 — `IObjectProvider.describeOutline`, `OutlineEntry`) ──────
    // A provider *describes* each object's table-of-contents entry; the core sorts, nests and draws.

    /** Longest outline label a provider may return (chars); the host truncates inward, the provider re-checks. */
    const val MAX_OUTLINE_LABEL_CHARS: Int = 200

    /** `OutlineEntry.level` is `0` (not an outline item) or `1..MAX_OUTLINE_LEVEL`. */
    const val MAX_OUTLINE_LEVEL: Int = 6

    /** Most payloads in one `describeOutline` call (the host chunks; the provider re-checks). */
    const val MAX_OUTLINE_BATCH: Int = 200

    /** Most payload chars, summed, in one `describeOutline` call (Binder transaction budget — a payload
     *  may be up to [MAX_OBJECT_TEXT_CHARS], so the host chunks by both count and chars). */
    const val MAX_OUTLINE_BATCH_CHARS: Int = 100_000

    /** Host cap on a whole notebook's outline (document order; the rest is dropped and the screen says so). */
    const val MAX_OUTLINE_ENTRIES: Int = 2_000

    /**
     * The exact `IllegalStateException` message an object provider throws from `createFromInk` when
     * the `recognizer` in-parameter it needs is null (no recognizer installed). The host compares
     * the message exactly and names the missing extension to the user; it never binds a provider
     * for an action whose `requires` bit says so, so this is the belt-and-braces path.
     */
    const val RECOGNIZER_REQUIRED: String = "recognizer required"

    /** Same for `render` when the `markdown` in-parameter it needs is null. */
    const val MARKDOWN_REQUIRED: String = "markdown required"

    // ── Selection-toolbar contributions + edit dialogs (arc 4 / H2 — `SelectionAction`, `EditSpec`) ──
    // Described by a provider, drawn by the host under its own e-ink rules. The host truncates strings,
    // caps lists and drops what it can't draw; the parcelables' own `require`s catch structural misuse.

    /** Most top-level [SelectionAction]s one provider may contribute; the host drops the rest. */
    const val MAX_ACTIONS: Int = 16

    /** Most sub-actions under one action (one level only — deeper nesting is dropped by the host). */
    const val MAX_SUB_ACTIONS: Int = 16

    /** Longest action id (chars); ids are `[A-Za-z0-9_.-]+`. */
    const val MAX_ACTION_ID_CHARS: Int = 32

    /** Longest action label (chars) — drawn as the button text when the icon name is unknown. */
    const val MAX_ACTION_LABEL_CHARS: Int = 6

    /** Longest long-press hint the host composes for a contributed button (label + provider label). */
    const val MAX_ACTION_HINT_CHARS: Int = 40

    /** Longest edit-dialog title / field hint (chars); the host truncates. */
    const val MAX_EDIT_TITLE_CHARS: Int = 40
    const val MAX_EDIT_HINT_CHARS: Int = 60

    /** Most characters an edit dialog's field accepts (an [EditSpec.maxChars] above this is clamped). */
    const val MAX_EDIT_TEXT_CHARS: Int = 4_000

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

    /**
     * The exact message of the `IllegalStateException` a recognizer throws from `recognize*` when it
     * could not become READY within the call (still acquiring its model, or nothing acquired yet). The
     * host types that one case ("still downloading"); any other `IllegalStateException` is an engine
     * failure. Recognizers must use this constant — the host compares the message, not a substring.
     */
    const val RECOGNIZER_NOT_READY: String = "recognizer not ready"

    /** Extension-namespaced template identity: `"<extension package>:<template id>"`. */
    fun templateIdentity(pkg: String, id: String): String = "$pkg:$id"

    /** Provider identity of an object: `"<extension package>:<typeId>"` (parsed by [parseIdentity]). */
    fun objectIdentity(pkg: String, typeId: String): String = "$pkg:$typeId"

    /** True if [s] is a well-formed object typeId: `[a-z0-9_-]+`, ≤ [MAX_TYPE_ID_CHARS] chars. */
    fun isTypeId(s: String): Boolean = s.length in 1..MAX_TYPE_ID_CHARS && TYPE_ID_PATTERN.matches(s)

    private val TYPE_ID_PATTERN = Regex("[a-z0-9_-]+")

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
