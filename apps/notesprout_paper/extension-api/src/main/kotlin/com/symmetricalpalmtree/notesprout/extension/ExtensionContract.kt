package com.symmetricalpalmtree.notesprout.extension

/**
 * The stable contract shared by the Notesprout Paper core (host) and every extension. This library
 * depends on nothing in `:app` and on no library beyond the Kotlin stdlib, so a third party can
 * consume it as a plain artifact.
 *
 * v1 has seven extension points: [ACTION_TEMPLATE_PROVIDER] (interface `ITemplateProvider`),
 * [ACTION_NOTEBOOK_NAMER] (interface `INotebookNamer`, arc 2), [ACTION_HANDWRITING_RECOGNIZER]
 * (interface `IHandwritingRecognizer`, arc 3 — a *capability* point: the host binds it and lends it
 * to other extensions through a proxy), [ACTION_MARKDOWN_RENDERER] (interface `IMarkdownRenderer`,
 * arc 4 — a second capability point: markdown in, image out), [ACTION_OBJECT_PROVIDER]
 * (interface `IObjectProvider`, arc 4 — the generic content-object point; the two capabilities reach
 * a provider only as in-parameters of its calls), [ACTION_SCRATCH_PAD] (interface `IScratchPad`,
 * arc 6 — the first *screen-owning* point: the extension owns an off-paper Activity the host launches
 * for a result; ink crosses through the bound service, never the Intent) and [ACTION_LINK_PROVIDER]
 * (interface `ILinkProvider`, arc 7 — the core owns link *structure*, the extension owns link
 * *meaning*: an opaque payload it resolves into typed descriptions; its picker screen is a second
 * tier-2 screen, and `ILinkCatalog` is the first host-implemented callback binder). `IExtensionStore`
 * (arc 2) is not a point — it is the host-owned store handed *to* an extension as an in-parameter of
 * a call; its caps are the `STORE_*` constants below.
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

    /** Intent action a scratch-pad `<service>` declares in its intent-filter (arc 6 / S0). */
    const val ACTION_SCRATCH_PAD: String =
        "com.symmetricalpalmtree.notesprout.extension.SCRATCH_PAD"

    /** Intent action the scratch-pad extension's exported screen `<activity>` declares; the host
     *  resolves it with `setPackage(<the discovered service's package>)` and launches it for a result. */
    const val ACTION_SCRATCH_PAD_SCREEN: String =
        "com.symmetricalpalmtree.notesprout.extension.SCRATCH_PAD_SCREEN"

    /** Intent action a link-provider `<service>` declares in its intent-filter (arc 7 / L0). */
    const val ACTION_LINK_PROVIDER: String =
        "com.symmetricalpalmtree.notesprout.extension.LINK_PROVIDER"

    /** Intent action the link extension's exported picker `<activity>` declares; the host resolves
     *  it with `setPackage(<the discovered service's package>)` and launches it for a result. */
    const val ACTION_LINK_PICKER_SCREEN: String =
        "com.symmetricalpalmtree.notesprout.extension.LINK_PICKER_SCREEN"

    /** Intent action the HOST's own New-notebook screen declares (arc 7 / L3 — the one host-owned
     *  screen an extension launches): the picker resolves it with `setPackage(HOST_PACKAGE)` and
     *  launches it for a result after arming it with `ILinkCatalog.prepareNewNotebook`. The screen
     *  checks its caller's signature; **no extra rides this Intent in either direction** — the
     *  folder + default name are parked by `prepareNewNotebook`, the created notebook is drained
     *  through `ILinkCatalog.takeCreatedNotebook`. */
    const val ACTION_LINK_NEW_NOTEBOOK_SCREEN: String =
        "com.symmetricalpalmtree.notesprout.extension.LINK_NEW_NOTEBOOK_SCREEN"

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

    /** Longest outline label a provider may return (chars) — structural: `OutlineEntry.requireValid` rejects a longer one at unmarshal (the reply "did not answer"); the provider cuts to it. */
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

    /** Largest value an extension may store (4 MiB — raised from 256 KiB in arc 6 / S0 for one-key-per-
     *  scratch-page). A value above [STORE_MAX_INLINE_BYTES] travels only through `putLarge` / `getLarge`
     *  (a [LargeValue] over `SharedMemory`) — a `byte[]` that size cannot cross a Binder. */
    const val STORE_MAX_VALUE_BYTES: Int = 4 * 1024 * 1024

    /** Largest value the `byte[]` `put` / `get` path carries (512 KiB — the Binder transaction budget).
     *  `put` above it → `IllegalArgumentException`; `get` of a stored value above it →
     *  `IllegalStateException` with the exact message [STORE_VALUE_LARGE]. */
    const val STORE_MAX_INLINE_BYTES: Int = 512 * 1024

    /** The exact `IllegalStateException` message `get` throws for a stored value above [STORE_MAX_INLINE_BYTES]. */
    const val STORE_VALUE_LARGE: String = "value is large — use getLarge"

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

    // ── Scratch pad (`IScratchPad`, arc 6 / S0) ──────
    // The screen's launch extras / result code, the ink-transfer caps (host-enforced outward before any
    // bind, re-checked inward on both sides) and the per-Binder-call chunk sizes.

    /** Boolean launch extra — true when the pad is opened from a notebook (the pad shows its Send buttons). */
    const val EXTRA_SCRATCH_SEND_ENABLED: String = "sendEnabled"

    /** Boolean launch extra — true right after a `receiveInk` (the pad opens on the received page, strokes selected). */
    const val EXTRA_SCRATCH_OPEN_RECEIVED: String = "openReceived"

    /** Activity result code: the pad has outbound ink for `takeOutgoing` (= `Activity.RESULT_FIRST_USER`). */
    const val RESULT_SCRATCH_SEND: Int = 1

    /** `receiveInk` placement: a new page after the pad's current page / the current page itself. */
    const val PLACEMENT_NEW_PAGE: Int = 0
    const val PLACEMENT_CURRENT_PAGE: Int = 1

    /** Most strokes / points (summed) in one transfer, either direction (S2: raised from 5 000 / 200 000 at the user's call). */
    const val MAX_TRANSFER_STROKES: Int = 10_000
    const val MAX_TRANSFER_POINTS: Int = 400_000

    /** Most strokes / points per Binder call (≈ 320 KB of floats — under the ~1 MB transaction budget
     *  with headroom); the host chunks, the extension re-checks ([InkBundle.requireValid]). */
    const val TRANSFER_CHUNK_STROKES: Int = 300
    const val TRANSFER_CHUNK_POINTS: Int = 20_000

    /** Most chunks the host drains on `takeOutgoing` (`ceil(MAX_TRANSFER_STROKES / TRANSFER_CHUNK_STROKES)`). */
    const val TRANSFER_MAX_CHUNKS: Int = 34

    /** The exact `IllegalStateException` message the scratch-pad extension throws from `receiveInk`
     *  when the target page's encoded ink would exceed [STORE_MAX_VALUE_BYTES]. */
    const val SCRATCH_PAGE_FULL: String = "scratch page full"

    // ── Link objects (`ILinkProvider` / `ILinkCatalog`, arc 7 / L0) ──────
    // The core owns link structure (rows, wrap/unwrap, render, gestures, navigation, undo); the
    // extension owns the payload (opaque to the core) and its meaning (`resolve` / `chromeOf`).

    /** Boolean launch extra on the picker screen — true when it pre-populates for an Edit. The ONLY
     *  extra: the payload itself never rides the Intent (it crosses through the held service). */
    const val EXTRA_LINK_EDIT: String = "editMode"

    /** Activity result code: the picker parked a [LinkChoice] for `takeResult` (= `Activity.RESULT_FIRST_USER`).
     *  Anything else = cancelled. */
    const val RESULT_LINK_PICKED: Int = 1

    /** Longest link payload (chars), both ways. The host truncates nothing — an over-cap payload is a
     *  refused result (`LinkChoice.requireValid` rejects it at unmarshal). */
    const val MAX_LINK_PAYLOAD_CHARS: Int = 2_000

    /** A link's chrome: nothing, or a 1 dp underline across the bounds' bottom (the default for new links). */
    const val LINK_CHROME_NONE: Int = 0
    const val LINK_CHROME_UNDERLINE: Int = 1

    /** [LinkDestination.kind]: a page of the link's own notebook / another notebook (its last-open
     *  page) / a specific page of another notebook. */
    const val DEST_PAGE: Int = 0
    const val DEST_NOTEBOOK: Int = 1
    const val DEST_NOTEBOOK_PAGE: Int = 2

    /** [CatalogEntry.kind]: a folder / a notebook / a page. */
    const val CATALOG_FOLDER: Int = 0
    const val CATALOG_NOTEBOOK: Int = 1
    const val CATALOG_PAGE: Int = 2

    /** Most entries one `ILinkCatalog` reply carries; the host drops the rest. */
    const val MAX_CATALOG_ENTRIES: Int = 2_000

    /** Longest [CatalogEntry.label] (chars); blank is legal (a page with no name). */
    const val MAX_CATALOG_LABEL_CHARS: Int = 200

    /** Longest id in a link parcelable — notebook / page / folder / catalog ids (the host's UUIDs are 36 chars). */
    const val MAX_LINK_ID_CHARS: Int = 64

    /** Most back-trail entries the trail methods keep; a push past it drops the oldest. */
    const val MAX_TRAIL_ENTRIES: Int = 50

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
