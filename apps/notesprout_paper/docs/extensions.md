# Extensions — the extension model & contract v1

> Arc: `PAPER_EXTENSIONS_PLAN.md` (the cross-session memory for the extensions work). This doc is the
> subsystem reference. **Phase E0** established the contract library and the first extension APK;
> **Phase E1** wired the host (`:app`) to discover and call them and removed the core's renderer;
> **Phase E2** reviewed and hardened both sides and froze this doc as the pattern every later extension
> point follows (§"Rules for adding a future extension point", §"Boundary audit").
> **Arc 2** (`PAPER_NAMING_PLAN.md`): **N0** added the host-owned encrypted extension store; **N1**
> added the second point, **NotebookNamer**, and the Naming extension (§"The Naming extension",
> §"NotebookNamer — host behaviour").
> **Arc 3** (`PAPER_RECOGNITION_PLAN.md`): **M0** added the third point — the engine-neutral
> **HandwritingRecognizer** *capability* point — and the ML Kit extension (§"HandwritingRecognizer
> (contract)", §"The ML Kit extension"); the host client + debug test surface come in M1.
> **Arc 4** (`PAPER_OBJECTS_PLAN.md`): **H0** added the fourth point — the **MarkdownRenderer**
> *capability* point (markdown in, image out) — and the Markdown extension (§"MarkdownRenderer
> (contract)", §"The Markdown extension"); nothing in the host binds it yet (H3/H4).
> **Arc 5** (`PAPER_CONTENTS_PLAN.md`, complete): **C0** appended `describeOutline` to `IObjectProvider`
> — the first exercised *compatible* AIDL change (`API_VERSION` still 1; the load probe + `OutlineCaps`
> tolerate an older provider); **C1** built the core-drawn Contents screen (`docs/notebook.md`
> §"Contents (arc 5)"); **C2** walked audit rows 25–27 and rule 24 (§"Adding an object point").
> **Arc 6** (`PAPER_SCRATCHPAD_PLAN.md`, S3 🧪 — code + docs complete, user verification pending): **S0** added the sixth point — **ScratchPad**, the
> first *screen-owning* point (an extension-owned off-paper Activity the core launches for a result;
> §"ScratchPad (contract)") — the shared **`:paper-screen`** module (the e-ink resources + screen
> helpers both the notebook and the pad use), the two appended store methods `putLarge` / `getLarge`
> (values to 4 MiB over `SharedMemory`; §"The extension store") and the `NSE · Scratch Pad` skeleton
> (`:ext-scratchpad`); **S1** built the screen (`ScratchPadActivity` — `docs/scratchpad.md`) and the two
> entry buttons (notebook top bar · library bottom bar, present only while the extension is installed);
> **S2** built the two ink transfers (the core `scratch` selection action + `receiveInk`; the pad's Send +
> `takeOutgoing` → paste selected, one undoable `Pasted`; §"ScratchPad (contract)" S2 state); **S3**
> reviewed the range, walked audit rows 28–32 (+ rows 1/6/7 for the held bind), and froze the pattern
> (§"Extension-owned screens (tier 2)", §"Adding a screen-owning point (arc 6 pattern)" rules 25–27,
> §"The Scratch Pad extension").

Notesprout's original design baked too many features into the core. Paper's core is **paper with
strokes** — a library of notebooks, each a stack of pages you write on. Everything else is added by
**extensions**: opt-in, removable, each a **separate APK** the core calls over Android IPC (AIDL).

The first extension is **Templates**: it offers the generated base templates (Lined / Dotted / Grid)
on the New-notebook screen and renders the chosen one into the WEBP the `.soil` already stores.
**Templates remain a core concept of the `.soil`** — a notebook shared with someone who lacks the
extension still shows its template, because the core reads and draws the stored WEBP exactly as before.
Only *how templates are offered and generated* lives in the extension.

There is **no Extensions UI yet**: extensions are installed and removed by hand (`adb install` /
`adb uninstall`, or Settings → Apps → Uninstall). An Extensions UI is a later arc.

---

## The extension model (concepts)

- **Extension** — an installed Android package. Its user-visible name is its application label
  (`ApplicationInfo.loadLabel`).
- **Extension point** — a named capability the core knows how to call. Each point has: an **intent
  action** (declared on a `<service>` intent-filter), an **AIDL interface**, and a **`<meta-data>` API
  version** on that `<service>`. One `<service>` per point per extension. **v1 has exactly one point:
  `TemplateProvider`.**
- **No launcher** — an extension declares **no launcher Activity**; it shows no icon in any app drawer.
  It is visible only under Settings → Apps (which is also how a user removes one until the Extensions UI
  exists). **Supernote is the exception:** Ratta's sidebar "Apps" grid and Settings → Apps → My Apps
  enumerate every user-installed *package*, launcher Activity or not, so an extension shows up there
  beside the app (tapping it does nothing). Accepted 2026-08-16 as the platform cost of separate APKs;
  the mitigation is the naming + icon convention below, so it at least reads as an add-on, not an app.
- **Naming + icon convention** — an extension's label is **`NSE · <Name>`** ("Notesprout Extension";
  debug builds append ` Dev`) and its icon is the **Tabler `puzzle`** outline in ink-black on white — the
  same visual vocabulary as the app's own launcher icon (the Tabler `seedling`, bare: "Paper" is a
  codename, the sprout is the brand). The prefix groups extensions in any alphabetical list and survives
  Ratta's ~18-character label truncation with the extension's own name intact; the puzzle says "not
  runnable" where the word can't. (`NPE` was rejected — it reads as NullPointerException.) Adaptive-icon
  vectors keep Tabler's stroke ratio (2 units per 24) with the glyph scaled ×3.1–3.4 and centred — sized
  like Ratta's own icons, inside the rounded-square mask, only brushing a circular one; a sprout inside
  the puzzle piece was tried and dropped — at launcher size it is a smudge.
- **Discovery** — the core runs
  `PackageManager.queryIntentServices(Intent(action), GET_META_DATA)`. The core manifest must declare
  the action in `<queries>` (mandatory on API 30+, or the query silently returns nothing). Disabled
  packages/components are not returned, so `pm disable` == uninstalled from the core's point of view.
- **Trust (v1: same-signature only)** — a discovered service is used only if the service is exported,
  its meta-data API version **equals** `ExtensionContract.API_VERSION`, and
  `PackageManager.checkSignatures(corePackage, extPackage) == SIGNATURE_MATCH`. The extension,
  symmetrically, refuses any caller whose uid is not the host package with a matching signature.
  Lifting to third parties later = one condition + the Extensions UI's consent step.
- **Calling** — the core binds (`bindService`, `BIND_AUTO_CREATE`), waits for `onServiceConnected` with
  a timeout, runs the AIDL call(s) on a background dispatcher under a timeout, and **unbinds in
  `finally`**. The core never holds a binding across screens; it binds per operation.
- **Payload rules** — the core hands an extension only what the call needs (for templates: page
  geometry + dpi). **Nothing about keys, files, the index, or notebook contents ever crosses.** Data
  coming back is untrusted: size-capped and bounded-decoded.

---

## Contract v1 (`:extension-api`)

`:extension-api` is an Android **library** (`namespace com.symmetricalpalmtree.notesprout.extension`,
`minSdk 29`, AIDL enabled) that **depends on nothing in `:app` and on no library beyond the Kotlin
stdlib**. Third parties will consume it as a published Maven artifact later; the module boundary keeps
that true. Dependency direction (Gradle-enforced, never violated): `:app → :extension-api` and
`:ext-templates → :extension-api`, `:ext-naming → :extension-api`, `:ext-mlkit → :extension-api`,
`:ext-markdown → :extension-api`, `:ext-heading → :extension-api`, `:ext-scratchpad → :extension-api`;
`:app` and the extension modules never depend on each other (`:ext-heading` reaches the recognizer and
the Markdown renderer **only through the proxies the core hands it as in-parameters**).

**`:paper-screen` (arc 6 / S0)** is the second shared library — **not** part of the contract: the
e-ink design-system resources (colors, dimens incl. the `sw720dp` tier, styles, `Theme.Notesprout`,
the toolbar / dialog drawables, every Tabler `ic_*` icon) and the screen helpers a paper-hosting
screen needs (`core/` `Slog` · `Device` · `TopGuard` · `Dialogs` · `ActionSheetDialog` · `StrokeCodec` ·
`InkColorCodec` · `Bitmaps`; `notebook/` `PageGestures` · `PageMath` · the generic `UndoRedoStack<A>` ·
`PaperToolbar` (was `NotebookToolbar`) · `PaperChrome` (was `NotebookChrome`; takes `extraRects` /
`extraContains` suppliers instead of the notebook's `SelectionToolbar`) · `ToolbarAnchor`), moved out
of `:app` **verbatim, packages unchanged** (`com.symmetricalpalmtree.notesprout.core` / `.notebook`
exist in two modules; no class is duplicated; the app's `R` keeps seeing the moved resources via
`android.nonTransitiveRClass=false`). It depends on **g-paper (`api`) + androidx only** — never on
`:app`, `:extension-api`, Room, SQLCipher or serialization; `:app` and `:ext-scratchpad` depend on it
(`:app → :paper-screen`, `:ext-scratchpad → :paper-screen`; the five earlier extensions stay
`:extension-api`-only). A fix to shared screen logic goes **there**, never in a consumer (the
original's sibling-copy trap). `Slog` gates on the library's own `BuildConfig.DEBUG` (the app's
debug build consumes the library's debug variant, so gating is unchanged); `:ext-scratchpad` may use
it, the five earlier extensions keep their `if (BuildConfig.DEBUG) Log.d` rule.

### `ExtensionContract`

| Constant | Value |
|---|---|
| `API_VERSION` | `1` |
| `ACTION_TEMPLATE_PROVIDER` | `"com.symmetricalpalmtree.notesprout.extension.TEMPLATE_PROVIDER"` |
| `ACTION_NOTEBOOK_NAMER` | `"com.symmetricalpalmtree.notesprout.extension.NOTEBOOK_NAMER"` (arc 2 / N1) |
| `ACTION_HANDWRITING_RECOGNIZER` | `"com.symmetricalpalmtree.notesprout.extension.HANDWRITING_RECOGNIZER"` (arc 3 / M0) |
| `ACTION_MARKDOWN_RENDERER` | `"com.symmetricalpalmtree.notesprout.extension.MARKDOWN_RENDERER"` (arc 4 / H0) |
| `ACTION_OBJECT_PROVIDER` | `"com.symmetricalpalmtree.notesprout.extension.OBJECT_PROVIDER"` (arc 4 / H3) |
| `ACTION_SCRATCH_PAD` | `"com.symmetricalpalmtree.notesprout.extension.SCRATCH_PAD"` — the `<service>` action of the scratch-pad point (arc 6 / S0) |
| `ACTION_SCRATCH_PAD_SCREEN` | `"com.symmetricalpalmtree.notesprout.extension.SCRATCH_PAD_SCREEN"` — the `<activity>` action of the extension-owned screen; the core resolves it with `setPackage(ref.packageName)` and launches it for a result (S0) |
| `META_API_VERSION` | `"com.symmetricalpalmtree.notesprout.extension.API_VERSION"` |
| `MIME_WEBP` | `"image/webp"` |
| `MAX_RENDER_BYTES` | `16 * 1024 * 1024` (16 MiB — hard cap the host enforces on a render result: a `RenderedTemplate` or a `RenderedImage`) |
| `MAX_MARKDOWN_CHARS` | `20_000` — longest markdown source one `IMarkdownRenderer.render` accepts (host truncates before the call; extension re-checks) (arc 4 / H0) |
| `MAX_IMAGE_EDGE_PX` | `4_096` — a `RenderedImage` may not exceed this on either side (host + extension) (arc 4 / H0) |
| `RENDER_PADDING_MAX_PX` | `64` — most padding (px, all four sides) a markdown render may ask for (arc 4 / H0) |
| `MAX_OBJECT_TEXT_CHARS` | `20_000` — longest object payload the host stores / hands to a provider (opaque; arc 4 / H1) |
| `MAX_ACTIONS` / `MAX_SUB_ACTIONS` | `16` / `16` — top-level `SelectionAction`s per provider / leaves per action (host drops the rest; arc 4 / H2) |
| `MAX_ACTION_ID_CHARS` / `MAX_ACTION_LABEL_CHARS` / `MAX_ACTION_HINT_CHARS` | `32` / `6` / `40` — action id (`[A-Za-z0-9_.-]+`) / label / the host-composed long-press hint (H2) |
| `MAX_EDIT_TITLE_CHARS` / `MAX_EDIT_HINT_CHARS` / `MAX_EDIT_TEXT_CHARS` | `40` / `60` / `4_000` — `EditSpec` title / field hint / most characters an edit field accepts (H2) |
| `MAX_TYPE_ID_CHARS` / `MAX_TYPES` | `32` / `16` — an object `typeId` is `[a-z0-9_-]+` (`isTypeId`) / most `describeTypes()` entries the host keeps per provider (H3) |
| `MAX_OBJECTS_PER_PAGE` | `200` — host cap on live objects per page (creation refused above it; H3) |
| `MAX_OUTLINE_LABEL_CHARS` / `MAX_OUTLINE_LEVEL` | `200` / `6` — an `OutlineEntry`'s label cap / `level` is `0` (not an outline item) or `1..6`. **Structural** — `OutlineEntry.requireValid` runs at unmarshal, so an over-long label or an out-of-range level rejects the whole reply (that provider "did not answer" this call, like every other malformed parcelable — rows 21 / 26); `OutlineCaps` re-clamps as defence in depth (arc 5 / C0, wording C2) |
| `MAX_OUTLINE_BATCH` / `MAX_OUTLINE_BATCH_CHARS` | `200` / `100_000` — most payloads / summed payload chars in one `describeOutline` call (the host chunks by both — a payload may be `MAX_OBJECT_TEXT_CHARS`; the provider re-checks) (C0) |
| `MAX_OUTLINE_ENTRIES` | `2_000` — host cap on a whole notebook's outline (document order; the rest is dropped and the Contents says so) (C0) |
| `RECOGNIZER_REQUIRED` / `MARKDOWN_REQUIRED` | `"recognizer required"` / `"markdown required"` — the exact `IllegalStateException` messages an object provider throws when the in-parameter it needs is null; typed on the host (`CapabilityRequiredException`) so the dialog can name the missing extension (H3) |
| `STORE_MAX_KEY_CHARS` | `512` — longest `IExtensionStore` key (the empty key is rejected) |
| `STORE_MAX_VALUE_BYTES` | **`4 * 1024 * 1024`** (4 MiB — was 256 KiB until arc 6 / S0) — largest `IExtensionStore` value; above `STORE_MAX_INLINE_BYTES` it travels only through `putLarge` / `getLarge` |
| `STORE_MAX_INLINE_BYTES` | `512 * 1024` — largest value the `byte[]` `put` / `get` path carries (the Binder transaction budget); `put` above it → `IllegalArgumentException`, `get` of a stored value above it → `IllegalStateException(STORE_VALUE_LARGE)` (S0) |
| `STORE_VALUE_LARGE` | `"value is large — use getLarge"` — the exact message of that `IllegalStateException` (S0) |
| `STORE_MAX_KEYS` | `50_000` — most keys one extension's store may hold |
| `MAX_NAME_CHARS` | `100` — host-side cap on a notebook name / scheme text an extension returns |
| `MAX_INK_STROKES` | `2_000` — most strokes in one `recognizeInk` / `recognizePage` call (host-enforced **before** the call, re-checked by the extension) |
| `MAX_INK_POINTS` | `60_000` — most points summed over the strokes of one call (≈ 480 KB of floats, under the ~1 MB Binder buffer) |
| `MAX_PRECONTEXT_CHARS` | `20` — the host truncates `preContext` to its tail before the call |
| `MAX_RECOGNIZED_CHARS` | `20_000` — host-side cap on the text a recognize call returns (the rest is dropped) |
| `EXTRA_SCRATCH_SEND_ENABLED` / `EXTRA_SCRATCH_OPEN_RECEIVED` | `"sendEnabled"` / `"openReceived"` — the scratch-pad screen's two boolean launch extras (the only data that rides its Intent; arc 6 / S0) |
| `RESULT_SCRATCH_SEND` | `1` (= `Activity.RESULT_FIRST_USER`) — the pad has outbound ink for `takeOutgoing` (S0; used in S2) |
| `PLACEMENT_NEW_PAGE` / `PLACEMENT_CURRENT_PAGE` | `0` / `1` — `receiveInk` placement (S0; used in S2) |
| `MAX_TRANSFER_STROKES` / `MAX_TRANSFER_POINTS` | `10_000` / `400_000` — most strokes / points (summed) in one scratch-pad ink transfer, either direction; host-enforced outward **before any bind**, re-checked inward on both sides (S0; raised from 5 000 / 200 000 in S2 at the user's call) |
| `TRANSFER_CHUNK_STROKES` / `TRANSFER_CHUNK_POINTS` / `TRANSFER_MAX_CHUNKS` | `300` / `20_000` / `34` — most strokes / points per Binder call (≈ 320 KB of floats); both sides chunk with the shared `InkChunks` (`:extension-api`), `InkBundle.requireValid` re-checks; the host's drain stops at 34 chunks and probes one more to learn whether anything was left (S0 / S2) |
| `SCRATCH_PAGE_FULL` | `"scratch page full"` — the exact `IllegalStateException` message the scratch-pad extension throws from `receiveInk` when the target page would exceed `STORE_MAX_VALUE_BYTES` (S0; used in S2) |
| `templateIdentity(pkg, id)` | `"$pkg:$id"` |
| `objectIdentity(pkg, typeId)` | `"$pkg:$typeId"` — the provider identity stored in an object row's `style` (H3); parsed by `parseIdentity` |
| `isTypeId(s)` | `[a-z0-9_-]+`, `1..MAX_TYPE_ID_CHARS` (H3) |
| `parseIdentity(s)` | `Pair<pkg, id>?` — splits at the **first** `:`; null if `:` absent or either side empty. The Blank sentinel is the host's, not the contract's. |

### AIDL

`extension-api/src/main/aidl/com/symmetricalpalmtree/notesprout/extension/`:

```aidl
// TemplateInfo.aidl
package com.symmetricalpalmtree.notesprout.extension;
parcelable TemplateInfo;

// RenderedTemplate.aidl
package com.symmetricalpalmtree.notesprout.extension;
parcelable RenderedTemplate;

// IExtensionStore.aidl — host-owned encrypted key/value store, scoped to the calling extension.
// Not an extension point: the host hands one in as a parameter of the calls that may need it.
interface IExtensionStore {
    /** Value for [key], or null if absent. Throws IllegalStateException(STORE_VALUE_LARGE) if the stored
     *  value is above STORE_MAX_INLINE_BYTES — use getLarge. */
    byte[] get(String key);
    /** Insert or replace. key 1..512 chars, value <= STORE_MAX_INLINE_BYTES (512 KiB — larger values go
     *  through putLarge), <= 50 000 keys per extension. */
    void put(String key, in byte[] value);
    /** Remove [key] (no-op if absent). */
    void delete(String key);
    /** Keys starting with [prefix] ("" = all), ascending. */
    List<String> keys(String prefix);

    // ── arc 6 / S0 — APPENDED after keys(); the four methods above keep their transaction codes ──
    /** Insert or replace a value up to STORE_MAX_VALUE_BYTES (4 MiB) carried in an ashmem region the
     *  caller created (RenderedTemplate's handshake: create, write, setProtect(PROT_READ), hand over;
     *  the host copies in and closes ITS handle at once; the caller closes its own). Same key / key-count rules as put. */
    void putLarge(String key, in LargeValue value);
    /** The value for [key] of any size (null if absent) as a read-only region the host created; the
     *  caller maps, copies out exactly byteCount bytes, and closes it. */
    LargeValue getLarge(String key);
}

// LargeValue.aidl (arc 6 / S0)
package com.symmetricalpalmtree.notesprout.extension;
parcelable LargeValue;

// SchemeField.aidl
package com.symmetricalpalmtree.notesprout.extension;
parcelable SchemeField;

// INotebookNamer.aidl — the NOTEBOOK_NAMER point (arc 2 / N1)
interface INotebookNamer {
    /** How the host should draw the scheme field (label, hint, one help line). No store needed. */
    SchemeField describeField();
    /** The scheme stored for [folderId], or null if none. */
    String currentScheme(IExtensionStore store, String folderId);
    /** null if [scheme] is acceptable, else a short user-facing error. Pure — no store. */
    String validateScheme(String scheme);
    /** Store [scheme] for [folderId]; "" (or blank) clears it. Throws IllegalArgumentException if invalid. */
    void saveScheme(IExtensionStore store, String folderId, String scheme);
    /** The default name for a new notebook in [folderId] given the folder's existing notebook names,
     *  or null if the folder has no scheme (host then uses its own default). */
    String defaultName(IExtensionStore store, String folderId, in List<String> siblingNames);
}

// ITemplateProvider.aidl
interface ITemplateProvider {
    /** Templates this provider offers, in display order. Ids are stable, ASCII, unique per provider. */
    List<TemplateInfo> listTemplates();
    /** Render [templateId] at exactly widthPx x heightPx for a panel of [dpi] as a lossless WEBP.
     *  Returns null if the id is unknown. Called on a Binder thread; may take seconds on e-ink CPUs. */
    RenderedTemplate render(String templateId, int widthPx, int heightPx, float dpi);
}

// InkStroke.aidl
package com.symmetricalpalmtree.notesprout.extension;
parcelable InkStroke;

// IHandwritingRecognizer.aidl — the HANDWRITING_RECOGNIZER point (arc 3 / M0).
// Engine-neutral. Every argument is bare geometry; every result is plain text. Stateless.
interface IHandwritingRecognizer {
    /** One of RecognizerStatus.* — READY / NEEDS_DOWNLOAD / DOWNLOADING / UNAVAILABLE. Fast. */
    int status();
    /** Start acquiring what the engine needs (model download). Returns at once; poll status().
     *  A no-op while READY or already DOWNLOADING. */
    void prepare();
    /** Recognize one writing area (no layout analysis). [strokes] in the area's px space,
     *  [areaWidth]/[areaHeight] > 0, [preContext] = the text just before this ink ("" if none).
     *  Returns the top candidate ("" if none). Throws IllegalStateException if status() != READY,
     *  IllegalArgumentException over the MAX_INK_* caps. */
    String recognizeInk(in List<InkStroke> strokes, float areaWidth, float areaHeight, String preContext);
    /** Recognize a whole page: the engine finds lines / paragraphs itself and chains context.
     *  [strokes] in page px; [pageWidth]/[pageHeight] the page size. Returns lines joined by '\n',
     *  paragraphs separated by a blank line ("" if nothing recognizable). Same exceptions. */
    String recognizePage(in List<InkStroke> strokes, float pageWidth, float pageHeight);
}

// RenderedImage.aidl
package com.symmetricalpalmtree.notesprout.extension;
parcelable RenderedImage;

// IMarkdownRenderer.aidl — the MARKDOWN_RENDERER capability point (arc 4 / H0).
// Markdown in, image out. Stateless. Lent to object providers by the core through a proxy (H3).
interface IMarkdownRenderer {
    /** Render [markdown] (≤ MAX_MARKDOWN_CHARS) as black text on a transparent background:
     *  natural width capped at [maxWidthPx] (> 0), [dpi] the panel density (sp/dp → px), [maxLines]
     *  0 = unlimited else ellipsize END past that many lines, [paddingPx] 0..RENDER_PADDING_MAX_PX added
     *  on all four sides. Returns a lossless WEBP with alpha whose declared size equals the encoded
     *  size, ≤ MAX_IMAGE_EDGE_PX per side; null if the source renders to nothing. Called on a Binder
     *  thread. IllegalArgumentException over the caps. */
    RenderedImage render(String markdown, int maxWidthPx, float dpi, int maxLines, int paddingPx);
}

// SelectionAction.aidl · EditSpec.aidl · CreatedObject.aidl (H2 / H3)
package com.symmetricalpalmtree.notesprout.extension;
parcelable SelectionAction;   parcelable EditSpec;   parcelable CreatedObject;

// OutlineEntry.aidl (arc 5 / C0)
package com.symmetricalpalmtree.notesprout.extension;
parcelable OutlineEntry;

// IObjectProvider.aidl — the OBJECT_PROVIDER point (arc 4 / H3). A provider owns one or more object
// types (typeIds). The core stores an opaque payload per object and asks the provider to act on it.
// Capabilities reach a provider ONLY as in-parameters — the core's proxies, or null when absent.
interface IObjectProvider {
    /** The typeIds this provider owns ([a-z0-9_-]+, ≤ MAX_TYPE_ID_CHARS, ≤ MAX_TYPES). Pure. */
    List<String> describeTypes();
    /** Selection-toolbar contributions in display order (≤ MAX_ACTIONS; one level of sub-actions). Pure. */
    List<SelectionAction> describeActions();
    /** For a selected object: which of this provider's action ids are "active" (drawn selected —
     *  e.g. the heading's current level). Pure; empty if none. */
    List<String> activeActionIds(String typeId, String payload);
    /** Turn a pure-stroke selection into an object. [actionId] = the tapped leaf action; [strokes] in
     *  page px, [areaWidth]/[areaHeight] = the selection bounds' size; [recognizer] = the core's proxy or
     *  null when none is installed (throw IllegalStateException(RECOGNIZER_REQUIRED) if it is needed).
     *  Returns the new object's typeId + payload, or null when nothing usable was recognized. */
    CreatedObject createFromInk(String actionId, in List<InkStroke> strokes, float areaWidth, float areaHeight,
                                IHandwritingRecognizer recognizer);
    /** Apply a leaf action to an existing object. Returns the new payload, or null for "no change". Pure. */
    String applyAction(String actionId, String typeId, String payload);
    /** How the core should draw the edit dialog for this object (null = not editable). Pure. */
    EditSpec describeEdit(String typeId, String payload);
    /** The payload after the user saved [text] in the edit dialog; null = no change (e.g. blank). Pure. */
    String applyEdit(String typeId, String payload, String text);
    /** Render the object: [maxWidthPx] > 0 (page width minus the object's x), [dpi] the panel density,
     *  [markdown] = the core's proxy or null when none is installed (throw
     *  IllegalStateException(MARKDOWN_REQUIRED) if it is needed). Returns null if there is nothing to draw. */
    RenderedImage render(String typeId, String payload, int maxWidthPx, float dpi, IMarkdownRenderer markdown);

    // ── arc 5 / C0 — APPENDED after render(); the eight methods above keep their transaction codes ──
    /** Outline (table-of-contents) entries for [payloads] of one of this provider's types — one
     *  OutlineEntry per payload, same order, same length: level 1..MAX_OUTLINE_LEVEL with a label
     *  ≤ MAX_OUTLINE_LABEL_CHARS, or level 0 (label ignored) for "not an outline item". Pure, ≤ 2 s;
     *  the host chunks at MAX_OUTLINE_BATCH / MAX_OUTLINE_BATCH_CHARS per call. A provider built
     *  before this method existed simply never receives it (the host tolerates the failure). */
    List<OutlineEntry> describeOutline(String typeId, in List<String> payloads);
}

// PaperStroke.aidl · InkBundle.aidl (arc 6 / S0)
package com.symmetricalpalmtree.notesprout.extension;
parcelable PaperStroke;   parcelable InkBundle;

// IScratchPad.aidl — the SCRATCH_PAD point (arc 6 / S0) — the first screen-owning point. The extension
// owns an off-paper Activity (ACTION_SCRATCH_PAD_SCREEN) the host launches for a result; the host HOLDS
// one bind on this service for the screen's whole showing (begin → launch → result → end → unbind),
// and every byte of ink crosses through these methods — never through the Intent. Every method:
// HostCallerCheck first. Timeouts are the host's (≤ 2 s each).
interface IScratchPad {
    /** The host is about to show the screen: hold [store] for the screen's life (revoked at end()). */
    void begin(IExtensionStore store);
    /** Notebook → pad (S2): one chunk of the inbound ink; [placement] (PLACEMENT_*) + [last] on every
     *  chunk. The extension appends chunks until last == true, then places them (a new page after the
     *  current one, or the current page) and marks them "open selected" for the next screen launch.
     *  Throws IllegalStateException(SCRATCH_PAGE_FULL) if the target page would exceed the store cap. */
    void receiveInk(in InkBundle chunk, int placement, boolean last);
    /** Pad → notebook (S2): after RESULT_SCRATCH_SEND the host drains the outbound ink chunk by chunk;
     *  an empty bundle (0 strokes) means done. */
    InkBundle takeOutgoing(int chunkIndex);
    /** The screen is over (result / cancel / host stop): drop the store, clear pending ink. */
    void end();
}
```

`RecognizerStatus` is a Kotlin `object` of `Int` constants (AIDL carries `int` — no parcelable, no
enum): `READY 0` (model on device, engine constructed) · `NEEDS_DOWNLOAD 1` (call `prepare()`) ·
`DOWNLOADING 2` (a `prepare()` is in flight) · `UNAVAILABLE 3` (the engine cannot run here). The host
treats any other value as `UNAVAILABLE`.

### Parcelables (hand-written — no `kotlin-parcelize`)

- `TemplateInfo(id: String, name: String)` — `writeString(id); writeString(name)`.
- `RenderedTemplate(memory: SharedMemory, byteCount: Int, mimeType: String)` —
  `writeParcelable(memory, flags); writeInt(byteCount); writeString(mimeType)`. The bytes are a complete
  WEBP file in `memory[0 until byteCount]`. Binder transactions are capped at ~1 MB, so a page bitmap
  can never travel as a plain `byte[]`; `SharedMemory` is ashmem-backed and Parcelable. **Handshake:**
  the **extension** creates the region (`SharedMemory.create(null, byteCount)`, maps RW, writes, unmaps,
  `setProtect(PROT_READ)`); the **host** maps read-only, copies out `byteCount` bytes, unmaps, closes.
  Writing the parcelable **dups** the region's file descriptor into the reply, so the extension closes
  its own handle once the transaction is marshalled (the Templates extension does this from
  `onTransact`'s `finally` via a per-Binder-thread `ThreadLocal`); leaving it to GC leaks one
  descriptor per render until a collection.

- `SchemeField(label: String, hint: String, help: String)` — `writeString ×3` (N1). How the host draws
  a namer's one text field: caption above, grey hint inside, one help line below. Untrusted on the host
  side — truncated to 40 / 60 / 200 chars before display.

- `InkStroke(x: FloatArray, y: FloatArray)` — `writeInt(n); writeFloatArray(x); writeFloatArray(y)`
  (M0). One stroke of **bare geometry** in the caller's px space; `require(x.size == y.size &&
  x.isNotEmpty())` runs in the constructor, so a malformed stroke is rejected at unmarshal time on the
  receiving side too. Nothing else is in it — no id, no time, no pressure, no colour, no width. A
  compatible tail (e.g. a time channel) may be appended after `y` later; readers of this version stop
  after `y`.

- `RenderedImage(memory: SharedMemory, byteCount: Int, mimeType: String, widthPx: Int, heightPx: Int)`
  — `writeParcelable(memory); writeInt(byteCount); writeString(mimeType); writeInt(widthPx);
  writeInt(heightPx)` (H0). The `IMarkdownRenderer` result: a complete lossless WEBP **with alpha** in
  `memory[0 until byteCount]` whose decoded size the sender *declares* as `widthPx × heightPx`. Same
  `SharedMemory` handshake as `RenderedTemplate` (extension creates + writes + `setProtect(PROT_READ)`,
  closes its handle in `onTransact`'s `finally`; host maps read-only, copies out, unmaps, closes).
  `RenderedImage.requireValid(byteCount, w, h)` runs in the constructor — `byteCount > 0`, positive
  size, both edges ≤ `MAX_IMAGE_EDGE_PX` — so a malformed reply is rejected at unmarshal time on the
  receiving side too (pure, JVM-tested). The host additionally **verifies** the WEBP header size
  (`Bitmaps.imageSize`) equals the declared size before decoding (H3). A compatible tail may be appended
  after `heightPx` later.

- `SelectionAction(id: String, label: String, iconName: String?, appliesTo: Int, requires: Int,
  subActions: List<SelectionAction>)` — `writeString ×3 (null-safe icon); writeInt ×2;
  writeTypedList(subActions)` (H2). One selection-toolbar contribution, drawn by the host (see
  §"Selection-toolbar contributions"). A **leaf** (no sub-actions) is what a provider is asked to
  perform; a **parent** opens the host's sub-toolbar and is never performed itself; nesting is one level
  (the host drops deeper). `SelectionAction.requireValid(id, label, appliesTo, requires)` runs in the
  constructor — id `[A-Za-z0-9_.-]+` ≤ 32, label non-blank ≤ 6, only known `ActionApplies` / `Requires`
  bits — so a malformed action is rejected at unmarshal time (pure, JVM-tested). Companion `object`s
  of `Int` bit flags: **`ActionApplies`** `INK = 1` (a pure-stroke selection) · `OBJECT = 2` (exactly one
  selected object of one of the provider's types); **`Requires`** `RECOGNIZER = 1` · `MARKDOWN = 2`.
  **`IconNames`** (`String` constants) is the host's icon catalog: `heading` `h-1`…`h-6` `text` `edit`
  `x` `check` `plus` `trash` `list` (arc 5 — the core's Contents glyph, listed so an extension may
  reuse it) `sketching` (arc 6 — the core's Scratch Pad glyph; was `notes` until the S1 follow-up: the two entry buttons and the core
  `scratch` action) — an unknown or null `iconName` draws the label as text.
- `EditSpec(title: String, text: String, hint: String, maxChars: Int, multiLine: Boolean)` —
  `writeString ×3; writeInt; writeInt(0/1)` (H2). How the host draws one object's edit dialog: title,
  a field prefilled with `text` (for a heading: the words **without** the `#`s), its `hint`, `maxChars`
  (`1..MAX_EDIT_TEXT_CHARS`, `requireValid` in the constructor with a non-blank title), single- or
  multi-line. Untrusted on the host side (`EditCaps` truncates before display).
- `CreatedObject(typeId: String, payload: String)` — `writeString ×2` (H3). What `createFromInk` returns:
  the new object's `typeId` (must be one of the provider's `describeTypes()` — the host checks in the same
  bind) and its opaque payload (≤ `MAX_OBJECT_TEXT_CHARS`, truncated by the host). `requireValid` in the
  constructor: well-formed `typeId` (`isTypeId`), non-blank payload (pure, JVM-tested).

All parcelables carry `@JvmField val CREATOR` and match their `.aidl` declarations.
`RenderedTemplate.describeContents()` and `RenderedImage.describeContents()` return
`CONTENTS_FILE_DESCRIPTOR` (they carry the region's fd — `Bundle.hasFileDescriptors()` relies on this);
- `OutlineEntry(label: String, level: Int)` — `writeString; writeInt` (arc 5 / C0). One object's outline
  entry from `describeOutline`: a `label` (≤ `MAX_OUTLINE_LABEL_CHARS`) at `level 1..MAX_OUTLINE_LEVEL`,
  or `level 0` = "not an outline item" (label ignored; `OutlineEntry.NONE`). `requireValid` in the
  constructor (level in `0..6`, label within the cap — thrown at unmarshal, which rejects the whole
  reply on the host). Untrusted on the host (`OutlineCaps`). Tails may be appended later.

- `LargeValue(memory: SharedMemory, byteCount: Int)` — `writeParcelable(memory); writeInt(byteCount)`
  (arc 6 / S0). A large extension-store value in `memory[0 until byteCount]` — the `RenderedTemplate`
  handshake **in both directions** (the sender creates the region, writes, `setProtect(PROT_READ)`,
  hands it over and closes its own handle once the transaction is marshalled; the receiver maps
  read-only, copies out `byteCount` bytes, unmaps, closes in a `finally`). `requireValid` in the
  constructor (so at unmarshal): `byteCount in 0..STORE_MAX_VALUE_BYTES` and `≤ memory.size` (an empty value rides a 1-byte region with `byteCount 0` — S3).
  `describeContents() = CONTENTS_FILE_DESCRIPTOR`. **`SharedBytes`** (`:extension-api`) writes the
  handshake once for both sides — `write(bytes): LargeValue` (create + map + copy + `PROT_READ`),
  `read(v): ByteArray` (map + copy exactly `byteCount` + unmap), `readAndClose(v)`.
- `PaperStroke(x, y, pressure, tilt: FloatArray, width: Float, colorArgb: Int, style: String)` —
  `writeInt(n); writeFloatArray ×4; writeFloat(width); writeInt(colorArgb); writeString(style)` (arc 6 /
  S0). A whole g-paper stroke **minus its id and time** for the scratch-pad transfers: four parallel
  channels in the authoring page's px space, the width (px), the colour and the `StrokeStyle` **name**
  (unknown → PEN on the reader's side — `TransferCaps.sanitize`). Nothing else travels: no id (both
  sides mint fresh ones), no time, no page id, no notebook id. `requireValid` in the constructor (so at
  unmarshal): four equal non-zero lengths, finite `width > 0` — a malformed stroke rejects the whole
  bundle it rides in (row 21's rule). Tails may be appended after `style` later.
- `InkBundle(strokes: List<PaperStroke>, pageWidth: Float, pageHeight: Float)` — `writeFloat ×2;
  writeTypedList(strokes)` (arc 6 / S0). One chunk of an ink transfer plus the page px geometry the
  strokes were authored in (`0f × 0f` = unknown → the reader uses its own page). `requireValid` in the
  constructor: `strokes.size ≤ TRANSFER_CHUNK_STROKES`, points summed `≤ TRANSFER_CHUNK_POINTS` (a
  **lone** stroke over the point chunk cap is its own chunk, bounded by `MAX_TRANSFER_POINTS`), sizes
  finite and `≥ 0`. An **empty** bundle is legal — it is how `takeOutgoing` says "done".

`TemplateInfo`, `SchemeField`, `InkStroke`, `SelectionAction`, `EditSpec`, `CreatedObject`, `OutlineEntry`,
`PaperStroke` and `InkBundle` return 0 from `describeContents()`; `LargeValue` returns `CONTENTS_FILE_DESCRIPTOR`.

### `HostCallerCheck` (N1 — shared extension-side trust gate)

`HostCallerCheck.enforce(context, hostPackage)` lives in `:extension-api` so every extension (first- or
third-party) gets the belt-and-braces caller check for free: `Binder.getCallingUid()` →
`getPackagesForUid(uid)` must contain `hostPackage` **and** `checkSignatures(uid, Process.myUid()) ==
SIGNATURE_MATCH`, else `SecurityException`. Every first-party extension calls it first in **every** stub
method with `BuildConfig.HOST_PACKAGE` (the per-build-type host id). It replaced the Templates
extension's private `CallerCheck` (the one permitted N1 touch to `:ext-templates`); it uses only
`android.*` — the library still depends on nothing.

**`HostCallerCheck.enforceActivity(activity, hostPackage): Boolean`** (arc 6 / S0) is the sibling for
an **extension-owned screen**: an exported Activity the host launches for a result calls it **first
thing in `onCreate`, before `setContentView`** — `activity.callingPackage == hostPackage` (set only for
a `startActivityForResult`-style launch; a plain `startActivity`, including `am start` from adb, leaves
it null and is refused) **and** `checkSignatures(callingPackage, packageName) == SIGNATURE_MATCH`. On
any refusal the Activity is `finish()`ed and `false` is returned; the caller returns from `onCreate`
at once without inflating anything. The core therefore launches such a screen **only** through an
`ActivityResultLauncher<Intent>` (risk register 2 of `PAPER_SCRATCHPAD_PLAN.md`).

### Versioning rules (for the next point / next version)

- A **new extension point** = a new action string + a new AIDL interface + the same `META_API_VERSION`
  key on its own `<service>`.
- A **compatible** change (a method appended at the end of an interface, an optional field appended to a
  parcelable's write order) keeps `API_VERSION`; the host must tolerate old extensions (catch the
  `RemoteException` from an unimplemented transaction). **First exercised in arc 5 / C0**
  (`IObjectProvider.describeOutline`, appended after `render`): an old provider's `Binder.onTransact`
  returns `false` for the unknown code and the generated proxy then reads an **empty reply** — no
  exception, an empty list — so "the host tolerates it" means **checking the reply's shape, not only
  catching**. Recipe: the host **probes** at provider load (`ObjectProviderClient.supportsOutline` =
  `describeOutline(firstType, [""])`, capable ⇔ a reply of exactly one entry; anything else — an
  exception, an empty reply, another length — is "not capable", logged `outline probe: unsupported`,
  never an error), records the capability per provider (`Contribution.outline`) **outside** the
  resume signature, and every real call validates length + content (`OutlineCaps.sanitize`: exact
  length or null). Verified against the arc-4 Heading APK on MIP11 (`outline=false`, no crash).
  **Second exercise (arc 6 / S0):** `IExtensionStore.putLarge` / `getLarge` appended after `keys()` —
  here the *host* implements the interface, so the compatibility question is the other way round: an
  extension built before S0 never calls the new codes, and one built after S0 calling into an older
  host cannot happen (the host is always the newest party in dev); no probe needed.
- An **incompatible** change bumps `API_VERSION`; the host then accepts a *range*
  (`MIN_API_VERSION..API_VERSION`) instead of exact equality.
- **Never** reorder or remove AIDL methods or parcel fields.

---

## The Templates extension (`:ext-templates`)

An Android **application** APK — `applicationId com.symmetricalpalmtree.notesprout.ext.templates`
(debug `.dev`), `versionName 0.1.0`, no NDK/native libs. `BuildConfig.HOST_PACKAGE` is
`com.symmetricalpalmtree.notesprout.dev` in debug and `com.symmetricalpalmtree.notesprout` in release
(the dev extension serves the dev core; release serves release). Dependencies: `:extension-api` +
`androidx.core:core-ktx`.

- **Label:** "NSE · Templates" (debug: "NSE · Templates Dev"). **Icon:** the Tabler `puzzle` outline,
  black on white (`drawable/ic_launcher_foreground.xml`; adaptive icon, white background) — per the
  convention above.
- **Manifest:** `android:allowBackup="false"`, **no Activity**, one exported `<service>`:
  ```xml
  <service android:name=".TemplateProviderService" android:exported="true">
      <intent-filter>
          <action android:name="com.symmetricalpalmtree.notesprout.extension.TEMPLATE_PROVIDER" />
      </intent-filter>
      <meta-data android:name="com.symmetricalpalmtree.notesprout.extension.API_VERSION" android:value="1" />
  </service>
  ```
- **`TemplateProviderService`** returns an `ITemplateProvider.Stub`. **Every** stub method first calls
  `HostCallerCheck.enforce(context, BuildConfig.HOST_PACKAGE)` (§`HostCallerCheck`): the caller uid must
  map to the host package **and** share this extension's signature, else `SecurityException`. `render` renders into a `SharedMemory` per the handshake above and parks the
  region in a `ThreadLocal`; the stub's `onTransact` override closes it in `finally`, after the reply
  (holding a dup of the descriptor) has been written. Binder threads call in — the stub holds no other
  mutable state.
- **`TemplateRenderer`** — the v0 core `BuiltInTemplates` moved **verbatim** (same geometry: 8 mm
  spacing at device dpi, mdpi-authored 1 px rule / 2 px dot radius scaled by dpi, LINED top margin
  2×spacing, symmetric GRID origin at 1×spacing) + the WEBP encode (`WEBP_LOSSLESS`, quality 100 —
  `WEBP_LOSSLESS` exists from API 30, so on API 29 the legacy `WEBP` at quality 100 is used, which is
  lossless too). The catalogue is one `enum Kind(id, nameRes)`: `lined` "Lined", `dotted` "Dotted",
  `grid` "Grid" (ids ASCII lower-case; names from `strings.xml`) — id list, lookup, and names all derive
  from `Kind.entries`, so a template cannot be half-registered. **Blank is not a template** — it is the host's "no template" option.

---

## The extension store (arc 2 / N0 — `:app` `data/extstore/`)

**Where an extension keeps its own data.** The core owns **one encrypted key/value database per
extension package** and lends the extension a small binder over it; the extension never sees a key,
a passphrase, a path, or a `File`, and cannot open anything itself.

- **File:** `extensionStoreFile(ctx, pkg)` = `Garden/<ext package>.db` (`data/SoilFile.kt`, the only
  path constructor; `pkg` is the *installed* package name from discovery, guarded
  `[a-zA-Z0-9_.]+`). So the `.dev` and release builds of an extension get separate stores.
- **Encryption:** SQLCipher under the **global** passphrase (`KeySession.get()` — process RAM, set once
  the index is open; every caller is behind `IndexGuard`). Raw-key cache file id `ext:<pkg>`. Same
  passphrase / raw-key / lockout / "forget key" machinery as the index and every `.soil`.
- **Open-or-create — `ExtensionStores.open(ctx, pkg)`** (IO, `@Synchronized`, process-lifetime cache
  keyed by package, closed only by `closeAll()`). Missing / empty file → **create** exactly the
  `SoilDatabase.create` way (`SoilCrypto.roomFactory(pass)` → force-open → `KeyOpener.warm`) — the
  **third named create entry point** (`docs/crypto.md` audit item 2). Existing file →
  `SoilCrypto.requireExisting` → `KeyOpener.roomFactoryFor` (cached raw key verified against the file,
  passphrase fallback + warm) — the `SoilDatabase.open` way. Every factory is
  `NonDestructiveOpenHelperFactory`-wrapped by `SoilCrypto`. No global key in session →
  `SoilLockedException`.
- **Schema:** `ExtensionStoreDatabase` (Room v1, WAL, `busy_timeout=5000`):
  `kv(key TEXT PRIMARY KEY, value BLOB NOT NULL, updatedAt INTEGER NOT NULL)`. The extension
  serialises whatever it wants into `value`. No namespaces, no extension-defined SQL.
- **Handoff:** `IExtensionStore` is passed as an **in-parameter** on each AIDL call that may need it.
  The host mints one `ExtensionStoreBinder(db, extUid)` per bind (`extUid` =
  `PackageManager.getPackageUid(pkg)` at bind time) and **`revoke()`s it in the same `finally` as the
  unbind**. Every method first checks `Binder.getCallingUid() == extUid && !revoked`, else
  `SecurityException` — the binder was handed to exactly one process and is dead after the bind.
  Stateless extension side; no reverse discovery, no exported host service.
- **Caps (host-enforced, `ExtensionContract.STORE_*`):** key `1..512` chars, value `≤ 4 MiB`
  (`STORE_MAX_VALUE_BYTES` — raised from 256 KiB in arc 6 / S0 for the scratch pad's one-key-per-page;
  **every** extension gets the larger cap), a `put` of a *new* key when the store already holds
  `50 000` → `IllegalStateException`; bad arguments → `IllegalArgumentException`; a DAO failure (SQLite full / locked / I/O) is rethrown as
  `IllegalStateException` (N2 — an exception Binder cannot marshal would fail the transaction
  *silently*: the extension would read an empty reply and believe its `put` succeeded). All are in
  the set Binder carries intact; the extension treats **any** exception as "store unavailable".
  `keys(prefix)` is an exact, case-sensitive "starts with" (`substr(key,1,length(prefix)) = prefix`
  — not `LIKE`, which is ASCII-case-insensitive; N2) returning ascending. Methods run synchronously
  on the host's Binder thread over the blocking DAO — never Main. (`ExtensionStoreGate` holds the
  checks with no Android types so they are JVM-tested; the `Stub` delegates to it.)
- **Large values (arc 6 / S0):** a `byte[]` of 4 MiB cannot cross a Binder (~1 MB transaction
  budget), so the interface has **two paths**: `put` / `get` carry values `≤ STORE_MAX_INLINE_BYTES`
  (512 KiB — `put` above it → `IllegalArgumentException`; `get` of a *stored* value above it →
  `IllegalStateException(STORE_VALUE_LARGE)`, "use getLarge"), and the two **appended** methods
  `putLarge(key, LargeValue)` / `getLarge(key): LargeValue?` carry any size up to the cap in an ashmem
  region — the `RenderedTemplate` handshake in both directions (`SharedBytes`). `ExtensionStoreGate`
  stays pure (`byte[]` in / out, JVM-tested: inline cap, large cap, `get` above inline throws,
  `getLarge` any size, the key-count cap on both puts); `ExtensionStoreBinder` does the copy around it
  — `putLarge` copies the extension's region in and closes the host's handle **at once**; `getLarge`
  wraps the bytes in a region the host creates and parks it per Binder thread so `onTransact`'s
  `finally` closes the host's handle **after** the reply (holding a dup of the descriptor) is written.
  Verified S0 (all three devices): the debug self-test's in-process 4 MiB round trip **and** a
  cross-process one run once from the scratch-pad extension's `begin` (MIP11 0.5 s · NA5C 0.9 s ·
  SNN 0.9 s, SharedMemory both ways over a real Binder — verified, then removed; the numbers are in
  `PAPER_SCRATCHPAD_PLAN.md` S0 Outcome).
- **Pre-open rule:** the host opens the store on IO **before** binding the extension for any call that
  carries one, so a cold open (KDF ≈ 0.5–1.5 s on e-ink when the raw key isn't cached) is never
  inside the extension call's 2 s timeout window.
- **Lifetime:** the `.db` **survives** the extension's uninstall / disable — removing an extension's
  data is Extensions-UI territory. Backup / restore / compaction: none in Paper (no backup subsystem).
  Debug "Forget cached key" clears `ext:*` raw keys with everything else.
- **Debug probe:** library ⋯ → "Extension store self-test" (`DebugMenu`, debug builds only) —
  open-or-create `probe.test`, round-trip through a real `ExtensionStoreBinder`, verify the encrypted
  header, wrong-uid and revoked refusal, **and (S0) a 4 MiB `putLarge` / `getLarge` round trip + the
  inline cap + `get`'s `STORE_VALUE_LARGE` refusal**; toast + `Slog.d("DebugMenu")` OK / FAIL with the
  timings.

## The Naming extension (`:ext-naming` — arc 2 / N1)

The second first-party extension and the first that **holds data**. It gives a folder a *naming
scheme*; a notebook created in that folder is pre-named by it. Folders without a scheme, the library
root, and every device without the extension keep the core default (`yyyyMMdd_HHmmss`) — the core's
behaviour without the extension is unchanged.

- **APK:** `applicationId com.symmetricalpalmtree.notesprout.ext.naming` (debug `.dev`), `versionName
  0.1.0`, Gradle/manifest shape identical to `:ext-templates` (no Activity, `allowBackup="false"`,
  `BuildConfig.HOST_PACKAGE` per build type, deps `:extension-api` only). Label **"NSE · Naming"**
  (debug "NSE · Naming Dev"), puzzle icon. One exported `<service android:name=".NotebookNamerService">`
  with the `NOTEBOOK_NAMER` action + `API_VERSION` meta-data `1`.
- **`NotebookNamerService`** returns an `INotebookNamer.Stub`; every method first calls
  `HostCallerCheck.enforce`. Holds no state. **Store key:** `folder:<folder UUID>` → UTF-8 scheme text;
  `saveScheme` with a blank scheme deletes the key. Any store failure is rethrown as
  `IllegalStateException` (an exception Binder carries intact) so the host sees a clean
  `ExtensionCallException` instead of a dead extension process. A stored scheme this version can't parse
  makes `defaultName` return null (host default) rather than throw.
- **Scheme language v1 (`SchemeEngine`, pure Kotlin, 18 JVM tests):** literal text + `{date}`
  (`yyyyMMdd`) + `{time}` (`HHmmss`) + `{n}` / `{n:K}` (K = 1–9; at most once). Literal text must satisfy
  the core's name rule (`[a-zA-Z0-9_\-. ]`); a literal-only scheme may not be `.`/`..`/blank; the whole
  scheme ≤ 100 chars; unknown `{…}`, a stray `}`, or an unclosed `{` are errors. Errors are enum codes
  the service maps to `strings.xml` (`err_*`), returned to the host verbatim for its problem dialog.
  **`{n}` = 1 + the highest number among sibling names that match the scheme's skeleton** — an
  anchored regex where literals are quoted, `{date}`/`{time}` are **wildcards** (`\d{8}` / `\d{6}` —
  the counter runs across days: `Meeting {date} {n:2}` → 01, 02 today, 03 tomorrow) and the counter is
  `(\d{1,9})`; zero-padded to K, never truncated (`{n:2}` after 99 → `100`).
- **Field wording** (`SchemeField` from `strings.xml`): label "Default notebook name" · hint
  "e.g. Meeting {date} {n:2}" · help "Tokens: {date} {time} {n} {n:3}. Leave empty for the standard name."

## HandwritingRecognizer (contract — arc 3 / M0)

The third point and the first **capability point**: an extension point whose implementation the core
binds itself and — in a later arc — *lends* to other extensions through a per-bind, uid-bound,
revocable proxy implementing the same `IHandwritingRecognizer` interface (the `IExtensionStore`
pattern; see §"The capability pattern" below). Extensions never bind each other.
Paper's core is "paper with strokes" and never learns what handwriting *says*: recognition results
are not stored anywhere by the core (no `page_text`, no `.soil` / index change).

- **Action** `ACTION_HANDWRITING_RECOGNIZER`; interface `IHandwritingRecognizer`; parcelable
  `InkStroke`; status constants `RecognizerStatus`. Engine-neutral — a TrOCR or Onyx-firmware
  extension can implement it later.
- **Outward payload — the recorded widening of boundary-audit row 3 for this point only:** per stroke
  the x/y point arrays (px), plus the writing-area or page size, plus ≤ `MAX_PRECONTEXT_CHARS` of
  pre-context. **Never** stroke ids, notebook/page ids, names, colour, width, style, pressure, tilt,
  timestamps, keys or paths. This is the first point that sends ink to an extension.
- **Protocol (as amended in M1, tightened in M2):** `status()` is fast and **never waits on the engine**
  (`DOWNLOADING` covers everything in flight — checking, downloading, loading); `NEEDS_DOWNLOAD` →
  `prepare()` (returns at once; idempotent, a no-op while `READY` / `DOWNLOADING`) — **the only call
  that may start a download** (M2: the host asks the user first, so nothing may download before it;
  M1's `onCreate` chain made the consent dialog cosmetic). `recognize*` may be called as soon as the
  extension exists: **if not READY it waits for an acquisition already in flight within the caller's
  timeout** — never starting one — and throws `IllegalStateException` with the contract message
  **`ExtensionContract.RECOGNIZER_NOT_READY`** if it cannot become ready in time (or nothing was
  prepared); any other `IllegalStateException` is an engine failure or timeout (so a real failure is
  never mistaken for a blank page). The host compares that message exactly (M2 — no substring
  sniffing; a third-party recognizer must use the constant). `UNAVAILABLE` means "don't bother".
  *Why the amendment:* M0 had `status()` block ≤ 1.5 s on ML Kit's `isModelDownloaded` and report a
  timeout as `UNAVAILABLE`; on the Nomad the first such check in a fresh process took ~75 s, so the
  first taps said "didn't respond" / "unavailable" for a model that was one 6 s download away. The
  original app avoids the whole ladder by running check → download → build once at startup, async;
  the extension now does the same on its first bind, and the recognize calls join that chain.
- **Two calls:** `recognizePage(strokes, pageW, pageH)` — the extension segments the page into lines
  and paragraphs itself and chains pre-context line to line (the core gains no HWR/layout knowledge);
  `recognizeInk(strokes, areaW, areaH, preContext)` — one writing area, no segmentation (the primitive a
  future consumer wants for a lasso'd selection or a heading). Both return plain text (`""` allowed).
- **Caps** (`MAX_INK_STROKES` 2 000 · `MAX_INK_POINTS` 60 000): a plain-parcel transport (`List<InkStroke>`
  in the transaction) — enforced **host-side before the call** (no bind over the cap) and **re-checked
  extension-side** (`IllegalArgumentException`). A `SharedMemory` transport is a later compatible
  change if a real page ever hits it.
- **Everything inward is untrusted:** status outside `0..3` → `UNAVAILABLE`; text `?: ""` and truncated
  to `MAX_RECOGNIZED_CHARS`.
- **Timeouts (host, M1):** bind ≤ 3 s · `status`/`prepare` ≤ 2 s · `recognizeInk` ≤ 10 s ·
  `recognizePage` ≤ 30 s.
- **No store:** the point is stateless (`en-US` only in v1; language selection is a later arc).
- **Logging rule (both sides):** recognized text is never logged — counts and durations only.

## The ML Kit extension (`:ext-mlkit` — arc 3 / M0)

> **H5 addendum — engine priming.** ML Kit loads the model into the recognizer lazily on the **first**
> `recognize` (1.5–4 s on the fleet). `ModelManager.buildClient` now runs one throwaway inference on a
> synthetic two-point stroke on a daemon thread right after the client is built (`prime`) — process
> start with a remembered model, or the end of the consent chain — so the first real call is warm. The
> result is discarded and never logged; a prime failure is a `Log.w` and changes no state. The host's
> cue is the H sub-toolbar opening (`ObjectActions.warm` → one `status()` bind, ≤ 1 per 20 s).

The first implementation of `HANDWRITING_RECOGNIZER`, using **Google ML Kit Digital Ink Recognition**
(`com.google.mlkit:digital-ink-recognition:19.0.0` — the same artifact + version the original Notesprout
ships; on-device, no Play Services required). **The dependency lives in `:ext-mlkit` only** — never
`:app`, never `:extension-api`.

- **APK:** `applicationId com.symmetricalpalmtree.notesprout.ext.mlkit` (debug `.dev`), `versionName
  0.1.0`, Gradle/manifest shape identical to `:ext-naming` (no Activity, `allowBackup="false"`,
  `BuildConfig.HOST_PACKAGE` per build type) plus the ML Kit dependency. Label **"NSE · ML Kit"** (debug
  "NSE · ML Kit Dev"), puzzle icon. One exported `<service android:name=".HandwritingRecognizerService">`
  with the `HANDWRITING_RECOGNIZER` action + `API_VERSION` meta-data `1`.
- **`HandwritingRecognizerService`** returns an `IHandwritingRecognizer.Stub`; every method first calls
  `HostCallerCheck.enforce`. `status()`/`prepare()` delegate to `ModelManager`; `recognizeInk` /
  `recognizePage` take a **whole-call budget** at entry (M2: `INK_BUDGET_MS` 9.5 s · `PAGE_BUDGET_MS`
  28 s — just under the host's 10 s / 30 s, so the extension stops at its own deadline instead of
  grinding on an orphaned Binder thread after the host has unbound), re-check the `MAX_INK_*` caps +
  positive sizes (`IllegalArgumentException`), then **wait for a chain already in flight**
  (`ModelManager.awaitReady` — 6 s for ink, 22 s for page; not ready by then →
  `IllegalStateException(RECOGNIZER_NOT_READY)`), then run `MlKitEngine` **synchronously on the
  Binder thread** with the deadline (`Tasks.await` per ML Kit call bounded to `min(10 s, time left)`;
  ML Kit's executor does the work). A timeout (deadline or wedged call) → `IllegalStateException
  ("recognition timed out")` — **not** an engine failure (M2: a slow first inference must never make
  the model look gone); any other engine failure → `ModelManager.onEngineFailure()` +
  `IllegalStateException`. Only Binder-marshalable exceptions leave the stub (arc-2 lesson).
- **`ModelManager`** (process-lifetime `object`) owns the `en-US` `DigitalInkRecognitionModel` and the
  client, and **one async ensure-ready chain**: `isModelDownloaded` → `download(model,
  DownloadConditions())` if needed (**any network**, as the original) → build the client. **Only
  `prepare()` starts it** (M2 — consent: the host's "Recognition model needed → Download?" dialog
  precedes it; in M1 the service's `onCreate` started the whole chain on the host's first `status()`
  bind, so the download had begun before the dialog and Cancel / the offline pre-check were cosmetic).
  It is idempotent while in flight and restartable after a failure (logged by class + duration).
  `status()` **never blocks**: `READY` (client built) · `DOWNLOADING` (chain in flight) ·
  `NEEDS_DOWNLOAD` (no chain / last chain failed) · `UNAVAILABLE` (no model identifier).
  `awaitReady(timeoutMs)` `Tasks.await`s a chain **already in flight** (or takes the flag shortcut) —
  what the recognize calls use; it never starts a download. **Model-present memory:** once the chain
  has seen the model on disk (present, or just downloaded) a flag is kept in the extension's own
  `SharedPreferences` (engine state — the same sandbox exception as the model); the service's
  `onCreate` (`warmUp()`) builds the client at once from the flag and is READY without ML Kit's cold
  `isModelDownloaded` (28 s on the Nomad the first time, ~4.6 s later). Without the flag the process
  starts **nothing** and reports `NEEDS_DOWNLOAD` — the host asks, `prepare()` runs the real chain,
  and a model that is in fact on disk (a build predating the flag, or the flag wiped) is found without
  a download (the progress dialog then just counts the check). An engine failure on the shortcut
  client **verifies** first (M2): one async `isModelDownloaded`; only `false` clears the flag and drops
  the client (`NEEDS_DOWNLOAD` → `prepare()` re-downloads); `true` marks the client verified and
  leaves it (the failure was transient); a timeout is never treated as an engine failure.
- **Where the model lives — the recorded exception to "extensions keep data in the host store":** the
  ~20 MB model is downloaded and managed by ML Kit **in the extension's own app storage**. Engine assets
  are not user data (and exceed the 256 KiB store value cap); user data still goes to the host store
  only. Uninstalling the extension removes the model; disable/enable keeps it.
- **`MlKitEngine`:** `recognizeInk` builds one `Ink` from the x/y arrays (`Ink.Point.create(x, y)` — no
  time channel), `WritingArea(max(w,1), max(h,1))`, `RecognitionContext(preContext = tail ≤ 20 chars)`
  → top candidate's text or `""`; every call takes the absolute deadline and waits
  `PageText.waitFor(deadline, now, 10 s)`. `recognizePage` = `StrokeSegmenter.segment(strokes)` → for
  each paragraph, for each line: `recognizeInk(line.strokes, line.bounds.width, layout.medianLineHeight
  (fallback: the line's own height), preContext = the previous recognized line)` → trimmed; a line that
  recognizes to `""` contributes nothing (no "unrecognized" placeholder); lines joined by `\n`,
  paragraphs by a blank line (`PageText.join`, pure + tested). **Per-line tolerance (M2, as the
  original `PageTextRecognizer`):** a line whose ML Kit call fails contributes nothing and the page
  goes on; only if *every* line failed is the first failure rethrown. Running out of the deadline is
  *not* tolerated per line — it aborts the page with a timeout (never a silent partial result; the
  warm retry has the model loaded). **Dots (M2):** `PageText.widenDots` promotes a single-point stroke
  (a pen tap — a period, an i-dot; g-paper does emit and draw them and `InkPayload` keeps them) to a
  degenerate two-point stroke before segmenting, because the verbatim segmenter drops `< 2`-point
  strokes (the original app's views never produced them). **Dot handling (`Dots`, M2 addendum, pure +
  4 tests):** ML Kit skips a pen-tap period or reads it as a comma (the original app has the same
  problem — same feed). (1) `Dots.round` — every *tiny* stroke (≤ 15 % of the line height in both
  directions: periods, i-dots; commas / apostrophes / hyphens are taller or wider — measured on the
  Nomad: periods 2–4 px, comma tails 10–15 px on a 62 px line) is redrawn as a 12-point circle at its
  centre before the ML Kit call (`recognizeInk` too, with the area height), instead of the 2–4 px
  lift-drag ML Kit half-reads as a comma tail or ignores. (2) `Dots.endsWithBaselineDot` +
  `fixTrailingPeriod` — in `recognizePage`, when a line's right-most stroke (by centre x) is a
  period-shaped mark that starts no more than 15 % of the line height inside the last letter's right
  edge (slant), the text is made to end in `.` (`,` → `.`, missing → appended; `. ! ? : ; …`
  untouched). Period-shaped = **tiny** and below 45 % of the band (an i-dot sits at ~25 %), **or** a
  **shaky period** — *small* (≤ 15 % wide, ≤ 30 % tall; measured 3 × 10–15 px on the user's 62 px
  lines, where every real comma was taller or wider than that) with its centre in the bottom 30 % of
  the band and **no tiny stroke above it** (the other small word-ender is an i-stem, 4 × 16 px on the
  same page, and an i-stem always has its dot). Descent below the baseline was measured and is *not*
  a discriminator (shaky periods hang +9/+15 px, clean ones +1..+11). Mid-line periods are not
  corrected. A debug-only geometry line per recognized line (`MlKitEngine` tag: stroke count, line
  height, last-stroke box + band position, tiny count, every small stroke's size + position, the
  last-char *class*, whether the rule fired) — **never the text**. Verified on the user's SNN test
  page: 12 periods (7 skipped by ML Kit, 2 read as commas) → `.`; 3 real-comma lines and a bare
  `Hi` untouched.
- **`StrokeSegmenter`** (pure Kotlin, 8 JVM tests) — a **verbatim port** of the original
  `recognition/StrokeSegmenter` (`RectF` → the tiny pure `Box`, `LiveStroke` → `InkStroke`): vertical
  projection profile → writing bands; each stroke to its (nearest) band; lines ordered left→right;
  a ≤ 3-stroke fragment with > 0.4 vertical overlap folds into its neighbour; a blank gap > 0.9 × the
  median line height starts a new paragraph. Constants unchanged: `PARA_GAP_FRAC 0.9` ·
  `BAND_COVERAGE_FRAC 0.15` · `FRAGMENT_MAX_STROKES 3` · `MERGE_OVERLAP_FRAC 0.4`. The only
  differences from the original file: no logging (pure), and the AABB is computed once per stroke
  (`Box.of`) since `InkStroke` carries no precomputed box.

## MarkdownRenderer (contract — arc 4 / H0)

The fourth point and the second **capability point**: markdown text in, an image out. Like the
recognizer, the core binds it itself (H3: `MarkdownClient`) and *lends* it to object providers
through a per-bind, uid-bound, revocable proxy implementing the same `IMarkdownRenderer`
(`MarkdownProxyBinder`, H3) — the Heading extension renders its payload through that proxy without
ever knowing which renderer is installed. Extensions never bind each other. The core has no
markdown knowledge of its own and never draws text on the page itself; it draws the returned image.

- **Action** `ACTION_MARKDOWN_RENDERER`; interface `IMarkdownRenderer`; parcelable `RenderedImage`.
  One method: `render(markdown, maxWidthPx, dpi, maxLines, paddingPx)`.
- **Semantics:** black text on a **transparent** background; the image's *natural* width (the widest
  line) capped at `maxWidthPx`; `dpi` converts the renderer's sp/dp typography to px (the core knows
  none of those numbers); `maxLines` `0` = unlimited, else END-ellipsized past that many lines (a
  heading passes `1`); `paddingPx` (`0..RENDER_PADDING_MAX_PX`) is added on all four sides **inside
  the image**, so the returned size includes it. **Blank source → `null`** (nothing to draw — no region
  is created, nothing crosses; H0 Q2). Encoding: **lossless WEBP with alpha** in the `RenderedTemplate`
  `SharedMemory` handshake (H0 Q1); the declared `widthPx × heightPx` must equal the encoded size.
- **Outward payload — nothing but the markdown text and the four layout numbers** (audit row 18, H5).
  Never ids, names, keys, paths, ink.
- **Caps** (host before the call, extension re-check → `IllegalArgumentException`): source ≤
  `MAX_MARKDOWN_CHARS`; `maxWidthPx` in `1..MAX_IMAGE_EDGE_PX`; `dpi > 0`; `maxLines ≥ 0`; padding
  in `0..RENDER_PADDING_MAX_PX`; result edges ≤ `MAX_IMAGE_EDGE_PX`, bytes ≤ `MAX_RENDER_BYTES`.
- **Stateless, no store**; every call on a Binder thread; **the markdown text is never logged on either
  side** — sizes, counts and durations only.
- **Timeouts (host, H3):** bind ≤ 3 s · `render` ≤ 5 s.

## The Markdown extension (`:ext-markdown` — arc 4 / H0)

The first implementation of `MARKDOWN_RENDERER`: a **verbatim port of the original Notesprout's
`core/markdown/`** pipeline (parser → spans → `StaticLayout`), rasterized into a bitmap instead of
drawn onto the page. Zero dependencies beyond `:extension-api` (no markdown library, no image
library).

- **APK:** `applicationId com.symmetricalpalmtree.notesprout.ext.markdown` (debug `.dev`),
  `versionName 0.1.0`, Gradle/manifest shape identical to `:ext-naming` (no Activity,
  `allowBackup="false"`, `BuildConfig.HOST_PACKAGE` per build type, deps `:extension-api` + junit).
  Label **"NSE · Markdown"** (debug "NSE · Markdown Dev"), puzzle icon. One exported `<service
  android:name=".MarkdownRendererService">` with the `MARKDOWN_RENDERER` action + `API_VERSION`
  meta-data `1`. ~2.5 MB.
- **`MarkdownRendererService`** returns an `IMarkdownRenderer.Stub`; `render` calls
  `HostCallerCheck.enforce` first, re-checks every cap (`MarkdownBitmap.Sizing.checkArgs` →
  `IllegalArgumentException`), renders, encodes, writes the WEBP into a `SharedMemory` parked in a
  per-thread `ThreadLocal` and closed in `onTransact`'s `finally` (the Templates pattern, verbatim),
  and returns `RenderedImage(shared, bytes, MIME_WEBP, bitmap.width, bitmap.height)`. Blank / empty
  result → `null`. Debug log: sizes + durations, never the text.
- **`MarkdownParser`** — verbatim port (pure Kotlin): blocks heading 1–6 · paragraph · list item
  (ordered / unordered / task, depth = two-space indent, ordered runs count from the first item's
  number) · blockquote · horizontal rule; inlines text · bold · italic · strikethrough · code · link
  (display text only) · image (alt text as italic). 21 JVM tests (the original's image + ordered-list
  suites ported, plus levels 1–6, emphasis, unclosed markers, list kinds, quote, rules, paragraph
  joins, blank source).
- **`MarkdownSpans`** — port of the original `MarkdownRenderer` (the one Android-text-only class):
  blocks → `SpannableStringBuilder` with stock spans. **Typography baked in here, not in the core:**
  headings **bold** at `headingSizeMultiplier` × base — **H1 2.0 · H2 1.75 · H3 1.5 · H4 1.25 · H5 1.1
  · H6 1.0**; list glyphs `• ◦ ▪` by depth, `☐ ☑` tasks, `n.` ordered, 16 dp indent steps; 3 dp
  quote stripe + 8 dp gap; 1 dp rule; code monospace; links underlined. `HeadingScaleTest`.
- **`MarkdownBitmap`** — port of the original `TextObjectRenderer` measure + draw as `render(markdown,
  maxWidthPx, dpi, maxLines, paddingPx): Bitmap?`: `TextPaint(ANTI_ALIAS)`, black, **base 24 sp**
  (`textSize = 24 × dpi / 160` px, `density = dpi / 160` for the dp spans); `StaticLayout` at
  `maxWidthPx − 2·padding`; natural width = the widest line (ceil) capped; height = the layout's;
  `maxLines > 0` → `setMaxLines + ellipsize END`; the trailing block `\n` trimmed before layout (else
  a spurious empty line inflates the height); `ARGB_8888`, transparent, text drawn at (padding,
  padding), clipped to the content box; blank source or empty layout → `null`. `encodeWebp` =
  `WEBP_LOSSLESS` q100 (API ≥ 30) else `WEBP` q100 (lossless — the E2 guard). The arithmetic is the
  pure `MarkdownBitmap.Sizing` (`checkArgs`, `contentWidth`, `imageSize` — throws over the edge
  cap), JVM-tested; only `render` / `encodeWebp` touch Android.
- **Verified H0 (install + `dumpsys` only — nothing binds it before H3):** SNN + NA5C + MIP11 —
  `MARKDOWN_RENDERER` resolves to `MarkdownRendererService`, no launcher activity, same debug
  signature as the app; the app still launches.

## Selection-toolbar contributions (contract — arc 4 / H2)

An object provider (the `OBJECT_PROVIDER` point, H3) puts buttons on the notebook's **floating
selection toolbar** — the bordered row that appears under a lasso selection — and describes the
**edit dialog** for its objects. It does so by *description only*: `SelectionAction` (id, ≤ 6-char
label, a catalog icon name, `appliesTo` / `requires` bits, one level of sub-actions) and `EditSpec`
(title, prefilled text, hint, `maxChars`, multi-line). **The core draws every button, sub-toolbar and
dialog itself**, under its own e-ink rules — tap dimens (`toolbar_button_size` squares), exclusion
rects, `releaseRender()` on every tap, pen gating (shown only via `whenPenIdle`, hidden during a
drag), frame silence, no colour, `state_selected` for an active sub-action. Icons come from the
core's `IconCatalog` by `IconNames` name; unknown → the label is drawn as text in the same square.

**The UI rule (tiered — locked 2026-08-17, Q4):**

1. **Description → core-drawn** is the only way an extension contributes UI *over the paper* — this
   arc's toolbar buttons, sub-toolbars and edit dialogs. No extension pixels, layouts or code reach the
   notebook screen.
2. **Extension-owned screens off the paper** (an Activity the core launches for a result and returns
   from) are the recorded future escape hatch for richer UI (Extensions-UI arc). Not built.
3. **Embedded remote UI (`SurfaceControlViewHost`, `RemoteViews`) and in-process extension code over
   the paper are never allowed.**

**Host caps (everything inward is untrusted; `ActionCaps` / `EditCaps`, pure + JVM-tested):** lists
capped at `MAX_ACTIONS` / `MAX_SUB_ACTIONS`; ids re-validated (`[A-Za-z0-9_.-]+`, ≤ 32, unique per
provider — first wins); labels trimmed + truncated to 6 (blank → dropped); `appliesTo` / `requires`
masked to known bits (`appliesTo == 0` → dropped); icon name → catalog drawable or null (label as
text); sub-actions of sub-actions dropped; the long-press hint = `"<label> · <provider label>"` ≤ 40
chars; edit title ≤ 40, hint ≤ 60, `maxChars` clamped to `1..4 000`, prefill truncated to it. Which
buttons show for a selection is `SelectionActions.merge` (`docs/notebook.md` §"Selection toolbar"):
core **Delete** first, then providers in registry order, filtered by `appliesTo` — a stroke-only
selection shows `INK` actions of every provider; exactly one object shows the `OBJECT` actions of the
provider owning its type; anything mixed shows Delete only.

H2 verified the toolbar with a debug-only stand-in (`FakeContributions`, deleted in H5) before any
extension was behind it; H3 built `ObjectProviderClient` behind the same shapes and H4 wired it into
the notebook screen — the real Heading provider is the only contributor today.

## ObjectProvider (contract — arc 4 / H3)

The fifth point and the **generic content-object point**: an extension owns one or more object
*types*, the core owns the object *rows* (`docs/data.md` §"Object rows" — identity, provider identity
`<pkg>:<typeId>` in `style`, an **opaque** payload in `text`, bounds, z-order) and asks the provider to
act on a payload. The heading is the first implementation; a future Text / Shape / Link extension
implements the same AIDL. Locked shape (Q9): describe actions · create-from-ink · apply an action ·
describe / apply an edit · render.

- **Action** `ACTION_OBJECT_PROVIDER`; interface `IObjectProvider`; parcelables `SelectionAction`,
  `EditSpec`, `CreatedObject`, `RenderedImage`, `InkStroke`, `OutlineEntry` (arc 5). Eight methods
  (AIDL above) + one appended in arc 5 / C0:
  `describeTypes()` · `describeActions()` · `activeActionIds(typeId, payload)` ·
  `createFromInk(actionId, strokes, areaWidth, areaHeight, recognizer)` · `applyAction(actionId, typeId,
  payload)` · `describeEdit(typeId, payload)` · `applyEdit(typeId, payload, text)` · `render(typeId,
  payload, maxWidthPx, dpi, markdown)`.
- **Capabilities arrive only as in-parameters** (rule 23): `createFromInk` gets an
  `IHandwritingRecognizer` (the core's `RecognizerProxyBinder`) and `render` an `IMarkdownRenderer`
  (`MarkdownProxyBinder`) — **or null** when nothing is installed; a provider that needs one throws
  `IllegalStateException(RECOGNIZER_REQUIRED / MARKDOWN_REQUIRED)`, which the host types. A provider
  says what an action needs through `SelectionAction.requires` so the host can explain *before* binding.
  Providers never discover or bind the recognizer / renderer themselves (nor each other).
- **Semantics:** every method but `createFromInk` and `render` is **pure** (no capability, ≤ 2 s).
  `createFromInk` receives the lasso'd strokes in **page px, exactly as the core holds them**, plus the
  selection bounds' size as the writing area (H3 Q2 — the original's single-shot path); it returns
  `CreatedObject(typeId, payload)` or **null** for "nothing usable" (the core then leaves the ink alone).
  `applyAction` / `applyEdit` return the new payload or **null** for "no change". `describeEdit` null =
  not editable. `render` returns a `RenderedImage` (the same lossless-WEBP handshake — a provider that
  forwards to the Markdown proxy may return the proxy's reply **as-is**; the region is the reply) or null
  for nothing to draw. `activeActionIds` = the leaf ids to draw `state_selected` for a selected object.
- **Outward payload — the object's own payload + geometry + the two proxies**; `createFromInk` adds
  bare ink (row 14's widening re-recorded); never ids, names, keys, paths (audit row 19, H5).
- **Caps** (host before the call, provider re-check → `IllegalArgumentException`): payload ≤
  `MAX_OBJECT_TEXT_CHARS` both ways; ink `MAX_INK_*` (`InkCaps.check` before the bind, and again inside
  the recognizer proxy); edit text ≤ `MAX_EDIT_TEXT_CHARS`; render args as for the Markdown point.
  **Inward** (all untrusted, `ObjectProviderClient`): `describeTypes` filtered to `isTypeId` and capped at
  `MAX_TYPES`; `describeActions` through `ActionCaps` (icons via `IconCatalog`); `activeActionIds`
  id-validated; a `CreatedObject`'s `typeId` must be in the provider's own `describeTypes()` (checked
  in the same bind) and its payload is truncated; `describeEdit` through `EditCaps`; the rendered image
  through `RenderedImages.copyOut` (mime, byte count, header size == declared, edge cap).
- **Stateless, no store** (rule 18 — the core stores objects; a provider keeps nothing about a specific
  object); every call on a Binder thread; **payloads / recognized text are never logged on either
  side** — counts, sizes and durations only. Binder-marshalable exceptions only (`SecurityException`,
  `IllegalArgumentException`, `IllegalStateException`).
- **Timeouts (host):** bind ≤ 3 s · describe / apply / edit ≤ **2 s** · `createFromInk` ≤ **15 s** (one
  recognizer hop of 10 s inside + margin) · `render` ≤ **10 s** (one markdown hop — bind 3 s + call 5 s — inside + margin; H5 raised it from 8 s, which left no margin) · `describeOutline` **2 s × chunks, capped at 10 chunks (20 s)** in one bind (`OUTLINE_MAX_CHUNK_BUDGET`, C2) · the outline probe 2 s (its own bind; a *transient* failure marks the load partial so resume re-probes — C2).
- **`describeOutline(typeId, payloads)` (arc 5 / C0 — appended, compatible, `API_VERSION` stays 1):**
  the *description* behind the Contents (rule 24, C2). Batched per type: the host hands a provider all
  its objects' payloads of one `typeId` (chunked at `MAX_OUTLINE_BATCH` / `MAX_OUTLINE_BATCH_CHARS`
  per call, every chunk inside **one bind** — `ObjectProviderClient.describeOutline` /
  `describeOutlineAll` across all of a provider's types) and gets back **one `OutlineEntry` per payload,
  same order, same length**: `level 1..MAX_OUTLINE_LEVEL` + label, or `level 0` = not an outline item.
  Pure, ≤ 2 s per chunk. **Outward:** the provider's own payloads, grouped by type — never ids, page
  numbers, positions, names (the core keeps the geometry and page index). **Inward (`OutlineCaps`):**
  a reply of any other length → the provider "did not answer" (null; the Contents shows the failure
  dialog and does not open — C0 Q4); a label over the cap or a level out of range is rejected **at
  unmarshal** (`OutlineEntry.requireValid` — the same "malformed reply = did not answer" rule as every
  parcelable, row 21); `OutlineCaps.sanitize` then trims labels, maps a blank label with level ≥ 1 to
  level 0 and re-clamps as defence in depth. **A provider must answer `describeOutline` for every type
  it declares** — `level 0` for a type it does not outline; a thrown `IllegalArgumentException` for one
  of its own types fails the whole bind (the reference Heading has one type, so its `require(typeId ==
  "heading")` is the *unknown-type* guard, not a refusal — C2 review). The **load probe** (`supportsOutline`, one blank payload,
  capable ⇔ a one-entry reply) decides `Contribution.outline` / `ObjectProviders.hasOutline` — an old
  provider is "not capable", nothing else changes (§"Versioning rules"). The core builds the tree
  (`OutlineTree`, sort (page, y, x), six levels, orphans attach to the nearest shallower heading), caps
  at `MAX_OUTLINE_ENTRIES` and draws the Contents itself (C1). Logs on both sides: counts + durations
  — never a label or payload.

## The Heading extension (`:ext-heading` — arc 4 / H3)

The reference implementation of `OBJECT_PROVIDER`: the **heading** type. Depends on `:extension-api`
only — it never sees `:ext-mlkit` or `:ext-markdown`; the recognizer and the renderer reach it as the
core's proxies.

- **APK:** `applicationId com.symmetricalpalmtree.notesprout.ext.heading` (debug `.dev`), `versionName
  0.1.0`, Gradle/manifest shape identical to `:ext-markdown` (no Activity, `allowBackup="false"`,
  `BuildConfig.HOST_PACKAGE` per build type, deps `:extension-api` + junit). Label **"NSE · Heading"**
  (debug "NSE · Heading Dev"), puzzle icon. One exported `<service android:name=".ObjectProviderService">`
  with the `OBJECT_PROVIDER` action + `API_VERSION` meta-data `1`. ~2.5 MB.
- **`ObjectProviderService`** (`IObjectProvider.Stub`, `HostCallerCheck.enforce` first everywhere):
  `describeTypes()` = `["heading"]` · `describeActions()` = one parent **`heading`** (label `H`, icon
  `IconNames.HEADING`, `appliesTo = INK | OBJECT`, `requires = RECOGNIZER | MARKDOWN`) with leaves
  `h1`…`h6` (labels `H1`…`H6`, icons `h-1`…`h-6`, same flags — `HeadingActions`) ·
  `activeActionIds` = `["h<level>"]` from the payload · **`createFromInk`**: `recognizer == null` →
  `IllegalStateException(RECOGNIZER_REQUIRED)`; else `recognizer.recognizeInk(strokes, areaWidth,
  areaHeight, "")` (the lasso box is the writing area, no pre-context), newlines folded to spaces,
  trimmed; blank → **null**; else `CreatedObject("heading", HeadingText.withLevel(text, level))` ·
  `applyAction(h<n>)` = re-prefix (same level or blank words → null) · `describeEdit` =
  `EditSpec("Edit heading", the words without the #s, "Heading text", 500, single-line)` (H3 Q3) ·
  `applyEdit` = blank → null, else re-prefix with the current level (unchanged → null) · **`render`**:
  `markdown == null` → `IllegalStateException(MARKDOWN_REQUIRED)`; else `markdown.render(payload,
  maxWidthPx, dpi, maxLines = 1, paddingPx = round(8 × dpi / 160))` returned **as-is** (the heading
  never decodes pixels). A `RemoteException` from a proxy becomes `IllegalStateException`. Unknown
  action / bad args → `IllegalArgumentException`. Debug log: counts + durations, never text.
- **`HeadingText`** (pure, JVM-tested — the original's `HeadingObject` helpers widened to six levels):
  `prefix(level)` = `"#" × level + " "` (clamped 1..6) · `strip(payload)` removes `^#{1,6}[ \t]+` ·
  `withLevel(text, level)` = prefix + strip(fold(text)) · `levelOf(payload)` = the count of leading `#`s
  (1..6; anything else — including seven `#`s, which markdown does not treat as a heading — is
  malformed → 1) · `fold(text)` = newlines → spaces, runs of spaces collapsed, trimmed ·
  **`outlineOf(payload)`** (arc 5 / C0) = `OutlineEntry(fold(strip(payload)).take(200), levelOf(payload))`,
  or `OutlineEntry.NONE` when the words are blank or a bare `#`-run (a heading is never level 0
  otherwise; a malformed payload is level 1). `HeadingTextTest` (7).
- **`describeOutline(typeId, payloads)`** (C0): `HostCallerCheck.enforce` first; `typeId != "heading"`,
  a null list, more than `MAX_OUTLINE_BATCH` payloads or more than `MAX_OUTLINE_BATCH_CHARS` summed →
  `IllegalArgumentException`; else `payloads.map(HeadingText::outlineOf)` — same length, same order.
  Debug log `describeOutline n=<count> in <ms>` — never a label.
- **Verified H3 (Claude-side, all three devices):** `OBJECT_PROVIDER` resolves to
  `ObjectProviderService`, no launcher activity; the debug ⋯ **Probe** binds it and logs
  `types=[heading]`, `actions=1 (6 sub)`, `active=[h2]` for a `##` payload, `edit=title 12 chars, text
  13 chars, max 500`, a `render` **through the Markdown proxy** (a `MarkdownRendererService` line inside
  the `ObjectProviderService` call — 502×126 px @ 280 dpi on MIP11, 538×136 / 539×136 @ 300 dpi on
  SNN / NA5C) and a `createFromInk` of the page's ink **through the recognizer proxy** (a
  `HandwritingRecognizerService` line inside); binds = unbinds, no `SecurityException`, no text in any
  line; with `NSE · Markdown` disabled, `render` fails typed as `CapabilityRequiredException(MARKDOWN)`
  and nothing crashes.

## ScratchPad (contract — arc 6 / S0)

The sixth point and the first **screen-owning** one — the second tier of the UI rule recorded in
arc 4 (description → core-drawn is the only UI *over* the paper; an extension-owned screen *off* the
paper that the core launches for a result is the escape hatch; in-process extension code over the
paper never). The core grows no second drawing surface: `NSE · Scratch Pad` owns `ScratchPadActivity`
(its own g-paper canvas, tools, pages — S1), keeps its pages in the host's extension store (one key
per page — hence the 4 MiB cap), and moves ink through the bound service (S2) — never through the
Intent. Shape (the recipe a later screen-owning extension follows; frozen in S3 as §"Extension-owned
screens (tier 2)"):

1. Discovery + trust as every point (`ExtensionRegistry.scratchPad` — **the first** trusted
   `ACTION_SCRATCH_PAD` service; a second installed pad is ignored, like the namer).
2. The core **pre-opens** the store on IO, then **holds one bind** for the screen's life
   (`ExtensionBinder.hold` → `HeldBinding`), calls `begin(store)` (≤ 2 s), and only then launches the
   screen with an `ActivityResultLauncher<Intent>` — after `paper.releaseForHandoff()` on any
   paper-hosting caller (S1).
3. The screen is an **exported Activity with a custom action (`ACTION_SCRATCH_PAD_SCREEN`) and no
   launcher filter**; it verifies its caller **first thing in `onCreate`** —
   `HostCallerCheck.enforceActivity` — else it is finished before anything is inflated. It reads only
   the recorded `EXTRA_*` and returns only the recorded `RESULT_*`.
4. On the result (any code), on the launcher's cancel, and in the caller's `onDestroy` while the
   screen is up: `end()` → unbind → revoke the store binder, in `finally` (`ScratchPadClient.finish`).
   The extension treats a dead binder / a `SecurityException` from the store as "unavailable" → an
   honest dialog and `finish()`.
5. The core decides what the user sees on every failure (rule 3): the extension's screen shows dialogs
   **only about its own state** (page full, store unavailable); the core owns the dialogs around the
   transfers.

**S0 state:** the AIDL (`IScratchPad` — `begin` / `receiveInk` / `takeOutgoing` / `end`), the two
parcelables (`PaperStroke`, `InkBundle`), the constants, `HostCallerCheck.enforceActivity`,
`ExtensionRegistry.scratchPad`, `ExtensionBinder.hold`, `ScratchPadClient.open` / `finish`,
`TransferCaps` (pure: `withinLimits` · `chunk` · `sanitize` · `toPaperStrokes` / `toStrokes` — fresh
ids on the way in), and the `NSE · Scratch Pad` skeleton (`:ext-scratchpad`: `ScratchPadApplication`
registers the g-paper engines in its own process; `ScratchPadService` with `begin` / `end` real and
`receiveInk` / `takeOutgoing` throwing `UnsupportedOperationException` until S2; `ScratchSession`;
`ScratchStore` — key layout `pages` / `current` / `page/<id>`, `put` up to the inline cap else
`putLarge`, the "page full" rule; `ScratchPageCodec` (a page blob = a header + `StrokeCodec`
format-B strokes; JVM-tested); `ScratchPages` (JVM-tested); a caller-checked placeholder Activity
showing "Scratch Pad" + Back). The host's debug ⋯ "Probe scratch pad" (removed in S3) exercises hold →
`begin` → `end` → unbind. Verified S0 on SNN / NA5C / MIP11 — see `PAPER_SCRATCHPAD_PLAN.md` S0
Outcome.

**S1 state:** the screen is real — `ScratchPadActivity` (caller-checked, full-bleed g-paper in the
extension's process, the notebook's chrome shape, `PageGestures` / `UndoRedoStack` / `PaperChrome` /
`ToolbarAnchor` from `:paper-screen`, pages + saves + the full rule through `ScratchDocument` over
`ScratchStore` — `docs/scratchpad.md`); the host's two entry points (`notebook/ScratchPadFlow` —
`releaseForHandoff()` immediately before the launch — and `library/ScratchPadLaunch`), both **present
only while a trusted `SCRATCH_PAD` extension is installed** (re-discovered on every resume). The
Send buttons show when opened from a notebook but are inert until S2.

**S2 state — the two transfers** (`docs/scratchpad.md` §Transfers, `docs/notebook.md` §"Scratch Pad
(arc 6)"). *Notebook → pad:* the core's own `scratch` selection action ("Pad", `appliesTo = INK`,
listed by `ScratchPadFlow.toolbarAction()` only while the extension is installed; `SelectionActions.merge`
filters core actions by `appliesTo` too) → the placement sheet → `TransferCaps.withinLimits` **before
any bind** → `ScratchPadClient.open` → **`send(chunks, pageWidth, pageHeight, placement)`** = one
`receiveInk(bundle, placement, last)` per chunk ≤ 2 s on the held bind, the extension's
`IllegalStateException(SCRATCH_PAGE_FULL)` typed as `ScratchPageFullException` → launch with
`openReceived = true`. The extension (`ScratchPadService.receiveInk`) re-checks the running totals,
mints ids (`ScratchInk` — its own mapping; `:paper-screen` cannot host a shared one), places on the
Binder thread (`ScratchStore.receive`: a new page after the current one or appended to the current
page, that page made current; the full rule refuses the whole placement), and records page + ids for
the screen to open **selected** and as one `Pasted` on its stack. *Pad → notebook:* the pad's Send
(page / selection) flushes, parks the chunks in `ScratchSession.outbound`, `RESULT_SCRATCH_SEND`; the
host's `ScratchPadClient.drainOutgoing` = `takeOutgoing(i)` ≤ 2 s each under `TransferCaps.Drain`
(empty bundle / summed caps / 34 chunks + one probe; every chunk `requireValid` + `sanitize`), then
`finish`; the notebook pastes with fresh ids (`TransferCaps.toStrokes`) as one `NotebookUndo.Action.Pasted`,
**coordinates 1:1**, left selected (host-initiated `setSelection`); a cut drain → `scratch_truncated`.
Outward on `receiveInk` = bare geometry + width + colour + style name + the page px size; inward on
`takeOutgoing` = the same, untrusted; **no id, page, notebook or name ever crosses** (rows 29–30 are
walked in S3). Both directions are copies. The audit rows 28–32 / rules 25–27 (S3) follow.

## The Scratch Pad extension (`:ext-scratchpad` — arc 6)

`NSE · Scratch Pad` (`com.symmetricalpalmtree.notesprout.ext.scratchpad[.dev]`) — the first extension
that **owns a screen**: `ScratchPadService` (the `SCRATCH_PAD` point — `begin` / `receiveInk` /
`takeOutgoing` / `end`, `HostCallerCheck.enforce` first in every method) and `ScratchPadActivity`
(exported, custom action, no launcher filter, `HostCallerCheck.enforceActivity` first thing in
`onCreate`), both in the extension's own process; `ScratchPadApplication` registers the g-paper
engines there. It depends on `:extension-api` **and** `:paper-screen` (the only extension that does —
its chrome, gestures, undo stack, dialogs and icons are the notebook's), and g-paper arrives
transitively (≈ 25 MB APK on BOOX because of the Onyx SDK; manifest `tools:replace="android:label,
android:allowBackup"` + libc++ `pickFirsts`). Its pages live in the host's extension store (`pages` /
`current` / `page/<id>` — one key per page, ≤ 4 MiB encoded through `ScratchPageCodec`, the large path
above 512 KiB), held for the showing through the store binder `begin` brought; it has no file, prefs
or store of its own. The screen (tools, pages, swipe / arrows / two-finger insert, delete confirmation,
undo / redo, the 800 ms save cadence + leave / pause / finish flush, the page-full rule), the two
transfers (received ink lands selected on the lasso as one `Pasted` / `Page` step; Send = page or
selection, a copy, parked for `takeOutgoing`) and the handoff discipline are documented in
**`docs/scratchpad.md`** (frozen S3); the host's two entry points — notebook top bar (far right, before
the debug ⋯) and library bottom bar, **present only while the extension is installed**, re-discovered
on every resume — in `docs/notebook.md` §"Scratch Pad (arc 6)" and `docs/library.md`. Verified S0–S2 on
SNN / NA5C / MIP11; the S3 review findings are in `PAPER_SCRATCHPAD_PLAN.md` S3 Outcome.

## Host behaviour (`:app`, package `extension/`)

- **Manifest:** `<queries><intent><action android:name="…TEMPLATE_PROVIDER"/></intent></queries>` as a
  child of `<manifest>` — without it the discovery query is empty on API 30+.
- **`ExtensionRegistry.templateProviders(context)`** (IO): `queryIntentServices(Intent(action),
  GET_META_DATA)`; keeps a candidate only if `serviceInfo.exported`, `metaData[META_API_VERSION] ==
  API_VERSION`, and `checkSignatures(core, ext) == SIGNATURE_MATCH`; each rejection is a `Slog.d`
  (tag `ExtensionRegistry`). Callers treat discovery itself as fallible (a `PackageManager` failure is
  logged and means "no providers", never a crash). Returns `ProviderRef(component, packageName, label, apiVersion)` sorted by
  label then package. Disabled packages are not returned by the query.
- **`ExtensionBinder.call(appContext, ref, action, tag, asInterface, callTimeoutMs, bindTimeoutMs = 3 s, block)`**
  (M1 — **the one bind path**, extracted verbatim from the two verified client bodies; all three
  clients use it and none keeps a private copy): explicit intent (`action` + `component`),
  **`checkSignatures` re-run immediately before the bind**, `bindService(BIND_AUTO_CREATE)` on the
  **application** context, await `onServiceConnected` ≤ `bindTimeoutMs`, `asInterface(binder)` (null →
  failure), run `block` on IO under `withTimeout(callTimeoutMs)` in a supervisor scope, **unbind in
  `finally`**; every failure → **one** `ExtensionCallException`, `CancellationException` re-thrown.
  Bind/unbind `Slog.d` lines go under the **caller's** `tag`, so per-client log tags are unchanged.
  Payload rules stay in the clients; the namer's store pre-open + per-bind `ExtensionStoreBinder` +
  revoke wrap *around* the shared call (revoke in the client's own `finally`, right after the unbind).
- **`TemplateProviderClient(context, ref)`** — bind-per-operation over `ExtensionBinder`. `call(timeoutMs, block)`: explicit
  intent (`action` + `component`), **`checkSignatures` re-run immediately before the bind** (the
  package could have been replaced under a different key while the screen holding the `ProviderRef`
  was open — trust is not a discovery-time-only property), `bindService(BIND_AUTO_CREATE)` on the
  **application** context,
  await `onServiceConnected` ≤ **3 s**, run `block` on IO under `withTimeout`, **unbind in `finally`**.
  Because a Binder transaction can't be interrupted, the call runs in a supervisor scope: on timeout the
  caller resumes with an exception while the orphaned call finishes on its own IO thread and is
  discarded. `onServiceDisconnected` / `onBindingDied` / `onNullBinding` / `bindService == false` /
  `SecurityException` / `RemoteException` / timeout / bad payload → **one** `ExtensionCallException`
  (genuine coroutine cancellation is re-thrown, not wrapped). `list()` = 2 s; `render()` = **15 s**
  (e-ink CPUs; the lossless WEBP encode is the slow part) — copies out of the `SharedMemory` after
  checking `mimeType == MIME_WEBP` and `0 < byteCount ≤ min(MAX_RENDER_BYTES, memory.size)`, unmaps and
  closes the region on every path, then requires the bytes to decode (`Bitmaps.imageSize`, header-only
  probe) to an image of **exactly** the requested `widthPx × heightPx` — an undecodable or wrong-size
  payload is a failed render, never stored (a wrong-size template would be stretched onto every page
  forever); returns null only when the extension returned null. `list()` drops null elements (AIDL
  lists may carry them). Every bind /
  unbind is a `Slog.d` (tag `TemplateProviderClient`) — one pair per list and per render, no
  `leaked ServiceConnection` (verified SNN + NA5C).
- **`TemplateChoice(provider, id, name)`** with `identity` = `templateIdentity(provider.packageName, id)`.
  **Accepted:** the identity uses the *installed* package name, so the debug extension writes
  `…ext.templates.dev:lined` and a release one will write `…ext.templates:lined` — two labels for the
  same first-party template. Nothing reads these labels yet; the future template-switch consumer of
  `parseIdentity` must treat the `.dev` package as an alias of the release one (or the Extensions-UI arc
  namespaces by a stable provider id). Recorded here so it is a decision, not a surprise.
- **New-notebook screen** (`docs/library.md`): the section is `GONE` until a provider answers with at
  least one template; no extension → no section, blank notebook. Render happens **before** any file
  is created; failure = toast + stay on the screen, never a silent Blank. Payload outward: template id,
  page width/height px, dpi — nothing else.
- **Failure surface:** the core decides what the user sees; the only user-visible failure is the
  render toast on CREATE. Discovery/list failures are silent (log only).

### NotebookNamer — host behaviour (N1)

- **Manifest `<queries>`** gains the `NOTEBOOK_NAMER` action.
- **`ExtensionRegistry.notebookNamer(context): ProviderRef?`** — same discovery + trust filter as
  `templateProviders`; **the first by (label, package) is used**, any others are dropped with a `Slog.d`
  (choosing among providers is Extensions-UI territory).
- **`NamerClient(context, ref)`** — the `TemplateProviderClient` shape (explicit component, signature
  re-checked before every bind, `BIND_AUTO_CREATE` on the app context, bind ≤ 3 s, **every call ≤ 2 s**,
  supervisor scope, unbind in `finally`, every failure → `ExtensionCallException`) plus the store:
  `call(store = true)` runs `ExtensionStores.open(ctx, ref.packageName)` on IO **before** binding
  (pre-open rule), mints one `ExtensionStoreBinder(db, extUid)` (`extUid` from
  `PackageManager.getPackageUid` at bind time) and **revokes it in the same `finally` as the unbind** —
  a late call on a timed-out transaction fails closed. Methods: `describeField()` (strings truncated to
  40/60/200), `currentScheme(folderId)` (≤ `MAX_NAME_CHARS`), `validate(scheme)` (error text ≤ 200),
  `save(folderId, scheme)`, `defaultName(folderId, siblingNames)`. **Outward payload — the recorded
  widening of audit row 3:** the folder UUID (a random id, the store key) and, for `defaultName`, the
  names of the folder's existing notebooks; for `save`/`validate` the scheme text the user typed.
  Nothing else exists in the interface.
- **`LibraryActivity`** — `namerRef` is refreshed on every `onResume` (IO, a few ms; chrome doesn't
  depend on it) and, when a namer is found, **its store is pre-warmed** right there
  (`ExtensionStores.open` on IO, failure logged only) so the first +Notebook tap never pays the cold
  raw-key open (~125 ms on the Nomad) or the one-time KDF after a key wipe. All three entry points are
  **absent while `namerRef == null`**:
  - **+Notebook** in a folder: `NamerClient.defaultName(folderId, names of the listing's notebooks)`
    is resolved **before** `NewNotebookActivity` opens (no feedback — the tap takes a beat; a second
    tap during that beat is dropped); result → `EXTRA_DEFAULT_NAME`. Null / failure / root → the screen
    opens without the extra.
  - **New folder**: `describeField()` runs before the dialog shows (failure → the plain v0 dialog); the
    dialog gains caption + `EditText` + help (`SchemeDialogs.buildField`). CREATE: name validated as
    before → if the scheme is non-blank, `validate` (error, or "Naming extension didn't respond" if
    unreachable → problem dialog + stay) → `createFolder` → `save(folder.id, scheme)`; a save failure
    shows the problem dialog "Folder created — naming scheme not saved" and dismisses (the folder
    exists; retry from long-press). *(M2: these were toasts in N1 — swept to `Dialogs.problem` under
    the toast-vs-dialog rule, together with the name-problem toasts of the New-folder / Rename
    dialogs and the move collisions in `FolderPickerActivity`.)*
  - **Folder long-press** → **"Default notebook name…"** (Tabler `cursor-text`, before Rename):
    `describeField()` + `currentScheme(id)` are fetched first (failure → problem dialog, no scheme
    dialog); the dialog (`SchemeDialogs.showSchemeDialog`, titled with the folder name) prefilled with
    the current scheme; OK → blank clears via `save(id, "")`, else `validate` (problem dialog + stay)
    then `save`; unreachable → problem dialog, the scheme dialog stays.
- **`NewNotebookActivity`** — `EXTRA_DEFAULT_NAME`; `acceptDefaultName(candidate)` (JVM-tested) admits
  it only if `validateName == null && length ≤ MAX_NAME_CHARS`, else the screen's own default. The
  screen stays extension-agnostic (it never binds the namer).
- **Verified (N1, SNN log, adb `input` overhead subtracted):** one bind/unbind per call, store opened
  once per process then cached; +Notebook in a scheme folder — warm: tap→bind 13 ms, call 33 ms,
  New-notebook displayed ≈ 0.26 s after the tap (same as with no extension); extension process killed:
  +≈ 380 ms (process start — the one cost that isn't ours without holding a binding across screens,
  which the design forbids); app fully cold: ≈ 0.75 s with the resume pre-warm (was 0.85 s). No
  `leaked ServiceConnection`.

### HandwritingRecognizer — host behaviour (M1)

- **Manifest `<queries>`** gains the `HANDWRITING_RECOGNIZER` action.
- **`ExtensionRegistry.handwritingRecognizer(context): ProviderRef?`** — same discovery + trust filter;
  **the first by (label, package)** is used, others dropped with a `Slog.d` (choosing an engine is
  Extensions-UI territory).
- **`RecognizerClient(context, ref)`** — over `ExtensionBinder`, stateless (no store). Timeouts:
  `status()` / `prepare()` **2 s**, `recognizeInk` **10 s**, `recognizePage` **30 s** (one ML Kit call
  per line; the first call after the extension's process start also loads the model). **Outward caps
  run before the bind** — `InkCaps.check(strokes, w, h)` (pure, JVM-tested): `strokes.size ≤
  MAX_INK_STROKES`, `Σ points ≤ MAX_INK_POINTS`, every stroke non-empty with equal x/y lengths, `w`/`h`
  `> 0` (NaN fails); violations throw **`InkTooLargeException`** (an `ExtensionCallException` subclass)
  **without binding**; `preContext` is cut to its last `MAX_PRECONTEXT_CHARS`. **Inward is untrusted**
  (`InkCaps.status` / `InkCaps.text`): a status outside `0..3` → `UNAVAILABLE`; text `?: ""` and
  truncated to `MAX_RECOGNIZED_CHARS`; the extension's `IllegalStateException` → typed
  `RecognizerNotReadyException` when its message **equals** `ExtensionContract.RECOGNIZER_NOT_READY`
  (could not become ready within the call), else a generic `ExtensionCallException` (engine failure /
  timeout). Log tag `RecognizerClient`: bind/unbind, stroke/char counts, durations — **never text**.
- **`InkPayload.fromStrokes(List<Stroke>): List<InkStroke>`** (`notebook/`, pure, JVM-tested) — the
  **one** place page ink is reduced to bare geometry (audit row 14): x/y arrays per stroke; id, colour,
  width, style, pressure, tilt and time never leave; point-less strokes are skipped.
  `RecognizeContext(strokes, pageWidth, pageHeight)` is what the notebook screen exposes to its debug
  menu — the paper's `getStrokes()` (any thread) and the session's current page px size (the same
  values passed to `setPageSize`); no ids, no names, no session.
- **The debug surface** (`src/debug/…/notebook/NotebookDebugMenu.kt`, no-op twin in `src/release`;
  release builds have no user-visible change): `NotebookActivity` installs a ⋯ (dimen-driven,
  `bg_toolbar_button`, tooltip = "Debug tools") at the **end** of the top bar row (`topBarRow`, a
  weight-1 spacer before it) — inside `topBar`, so the existing exclusion rect covers it and
  `dispatchTouchEvent`'s chrome `releaseRender()` applies. Tap → `ExtensionRegistry.handwritingRecognizer`
  (IO, refreshed on every open) → `ActionSheetDialog` "Debug tools" with **"Recognize page (ML Kit)"
  only while a recognizer is installed** (none → the sheet opens with its title only). Flow (as
  settled with the user in M1 — **dialogs, not toasts**, for anything the user must notice; a toast
  only for a confirmation of something that already happened):
  - strokes empty → dialog *Nothing to recognize* · `InkPayload` (Default) → `status()`:
  - `READY` → **"Recognizing…"** popup (Opening-style: bordered message, no buttons, non-cancelable)
    → `recognizePage(ink, pageW, pageH)` → result `AlertDialog` titled **"Recognized text (ML Kit ·
    N strokes · T ms)"**, message = the text, selectable (`""` → *(nothing recognized)*), **Copy**
    (clip label "Recognized text", toast "copied") + **OK**.
  - `UNAVAILABLE` → dialog *Recognizer unavailable on this device*.
  - otherwise (`NEEDS_DOWNLOAD` / `DOWNLOADING`) → the **one-time model flow** (**since H3 this is
    `RecognizerReadiness.ensureReady` in main source — the debug menu calls it; H3 also sends an already
    DOWNLOADING status straight to the progress dialog**): `Connectivity.isOnline`
    pre-flight (ML Kit's downloader *hangs* rather than fails offline — M1: no error after a minute) —
    offline → dialog "Recognition model needed" saying the device is offline, OK only; online → the
    same dialog offering the ~20 MB en-US download (Wi-Fi recommended) with **Download** / **Cancel**.
    Download → `prepare()` → progress dialog **"Downloading recognition model"** whose message carries
    an **elapsed-time counter** (the e-ink-safe indeterminate indicator — no spinner; refreshed every
    2 s) and **Cancel** (hides the dialog only; the download keeps running in the extension). The
    dialog polls `status()` every 2 s (a bind per poll, ~30 ms — bind-per-operation, as decided in
    M1): `READY` → dismiss → the READY path above, no further tap · network gone → message *Waiting for
    a network connection…*, gives up after 30 s offline · `NEEDS_DOWNLOAD` after `prepare()` (chain
    failed) / `UNAVAILABLE` / 5-min cap / **5 consecutive failed polls** (M2 — an extension that stays
    unbindable is a failure now, not after the cap) → dialog **"Download failed"**. Nothing downloads
    before Download is tapped (M2 — `prepare()` is the only trigger).
  - `InkTooLargeException` → dialog *Page too dense to recognize*; `RecognizerNotReadyException` (READY
    reported, then lost — extension restarted mid-flow) → dialog *Model still downloading — try again
    in a minute*; any other `ExtensionCallException` → dialog *ML Kit extension didn't respond*.
  - One busy guard drops a second tap for the whole flow (dialogs included); it is **owned by the
    activity that started the flow** (a `WeakReference`, busy ⇔ owner alive — M2: a dialog torn down
    with its activity fires no `onCancel`, and a process-lifetime flag would have stayed stuck for
    every later tap). Nothing recognized is stored or logged — the result dialog is the only sink.
- **Failure surface:** every dialog/toast above is the core's; the extension shows nothing. Absent
  extension → no sheet item; not ready / timeout / too dense / offline → a dialog; the page, the ink
  and the other extensions are unaffected.
- **Timings (M1):** warm `recognizePage` of one line ≈ 0.5 s (Nomad) — cold extension process:
  process start + client build ≈ 1.9 s, then the **first inference loads the model** — 4.5 s on the
  Nomad, 1.8 s on the NoteAir5C — so ≈ 6 s / 4 s tap-to-dialog cold. A future consumer that wants
  it faster must bind early (and the extension would need a warm-up inference — deferred).

### MarkdownRenderer / ObjectProvider — host behaviour (H3)

- **Manifest `<queries>`** gains the `MARKDOWN_RENDERER` and `OBJECT_PROVIDER` actions.
- **`ExtensionRegistry.markdownRenderer(context): ProviderRef?`** — first by (label, package), the rest
  dropped with a `Slog.d`; **`ExtensionRegistry.objectProviders(context): List<ProviderRef>`** — every
  trusted provider, sorted by (label, package); all of them contribute.
- **`MarkdownClient(context, ref)`** — over `ExtensionBinder`, stateless. `render(markdown, maxWidthPx,
  dpi, maxLines, paddingPx): RenderedImages.Copy?` ≤ **5 s**. **Outward caps before the bind**
  (`RenderCaps`, pure + JVM-tested): the source is truncated to `MAX_MARKDOWN_CHARS`; `maxWidthPx` in
  `1..MAX_IMAGE_EDGE_PX`, finite `dpi > 0`, `maxLines ≥ 0`, padding in `0..RENDER_PADDING_MAX_PX` else
  **`RenderArgsException`** without a bind. **Inward** (`RenderedImages.copyOut`): mime == `MIME_WEBP`
  and `0 < byteCount ≤ min(MAX_RENDER_BYTES, region)`, copy out, unmap, then the WEBP header must decode
  to **exactly** the declared `widthPx × heightPx` within the edge cap; the region is closed on every
  path. Null only when the extension returned null. Log tag `MarkdownClient`: sizes + durations, never
  the text.
- **`ObjectProviderClient(context, ref, recognizerRef?, markdownRef?)`** — over `ExtensionBinder`,
  stateless. `describeTypes` / `describeActions` / `activeActionIds` / `applyAction` / `describeEdit` /
  `applyEdit` ≤ **2 s**; `createFromInk` ≤ **15 s**; `render` ≤ **10 s** (H5; was 8). **The two proxies are minted
  per bind and revoked in the client's own `finally`, right after the shared unbind** (the `NamerClient`
  store shape): `RecognizerProxyBinder(RecognizerClient(recognizerRef), extUid)` is passed only to
  `createFromInk`, `MarkdownProxyBinder(MarkdownClient(markdownRef), extUid)` only to `render`; each is
  **null** when its ref is null. Inward validation as listed under §"ObjectProvider (contract)".
  Typed failures: the provider's `IllegalStateException` whose message equals `RECOGNIZER_REQUIRED` /
  `MARKDOWN_REQUIRED` → **`CapabilityRequiredException(requires)`**; `RECOGNIZER_NOT_READY` (a proxied
  recognizer that could not become ready) → `RecognizerNotReadyException`; anything else → one
  `ExtensionCallException`. Log tag `ObjectProviderClient`: counts, sizes, durations — **never a payload**.
- **`ProxyGate(extUid, callingUid)`** (pure, JVM-tested) — the `ExtensionStoreGate` shape without the
  store: `check()` throws `SecurityException` unless the caller is the extension's uid and the gate is
  not `revoked`; `revoke()` from the client's `finally`. A late call from an orphaned transaction fails
  closed.
- **`RecognizerProxyBinder(client: RecognizerClient, extUid)`** (`IHandwritingRecognizer.Stub` — the
  arc-3 recipe, built here): every method `gate.check()` first; **`status()` forwards all four values and
  `prepare()` forwards** (H3 Q4 — user choice: a provider may trigger the acquisition; the core still
  runs `RecognizerReadiness` before the call); `recognizeInk` / `recognizePage` re-apply
  `InkCaps.check` + `preContext` truncation inward, then forward. Forwarding = **`runBlocking` on the
  host's Binder thread** (never Main); the inner `RecognizerClient` call has its own bind, timeout and
  signature check. Failures → the marshalable set: `RecognizerNotReadyException` →
  `IllegalStateException(RECOGNIZER_NOT_READY)`, `InkTooLargeException` → `IllegalArgumentException`,
  other `ExtensionCallException` → `IllegalStateException(<class>)`.
- **`MarkdownProxyBinder(client: MarkdownClient, extUid)`** (`IMarkdownRenderer.Stub`): same gate;
  `render` re-applies `RenderCaps.checkArgs` inward (`IllegalArgumentException`), forwards via
  `runBlocking`, and **re-wraps** the verified bytes into a fresh `RenderedImage` region the proxy owns
  (`RenderedImages.wrap`: map, write, `PROT_READ`; parked per Binder thread and closed in `onTransact`'s
  `finally` once the reply is marshalled — the Templates handshake, host-side).
- **`RecognizerReadiness.ensureReady(activity, client, onReady, onGaveUp, problemTitleRes)`** (main
  source since H3 — the M1/M2 consent flow moved out of `NotebookDebugMenu`, which now only calls it;
  the heading action uses it in H4): `status()` → READY → `onReady` · NEEDS_DOWNLOAD → "Recognition
  model needed" (offline pre-check → offline dialog) → Download → `prepare()` + progress dialog with
  the elapsed counter (2 s polls; 5 consecutive failed polls / 30 s offline / 5 min → "Download
  failed"; Cancel hides only) → READY → `onReady` · **DOWNLOADING → straight to the progress dialog**
  (consent was already given — the one deliberate difference from the debug menu's old else-branch) ·
  UNAVAILABLE → problem dialog. Exactly one of `onReady` / `onGaveUp` runs; the one-flow-at-a-time
  guard is the caller's. Strings are the M1 `recognize_*` set in main `strings.xml`.
- **`ObjectProviderClient.renderAll(requests, dpi): List<Copy?>`** (H4) — several objects of one provider
  in **one bind with one Markdown proxy** (the page-load render pass: one provider bind, N renders):
  args checked before the bind, budget `RENDER_TIMEOUT_MS × n`; one entry per request in order — the
  verified copy, or null when the provider drew nothing or that render failed (logged; the batch goes
  on) — except a `CapabilityRequiredException`, which ends the batch (rest null) and is re-thrown.
- **The notebook screen's use of it (H4 — `docs/notebook.md` §"Objects — actions, edit, render pass"):**
  `ObjectProviders` loads every trusted provider once per open (`describeTypes` + `describeActions`;
  refreshed on resume when the discovery signature of the three points changed); `ObjectActions` runs
  the `requires` guards (recognizer named first, then Markdown, then the page cap), `RecognizerReadiness`
  before `createFromInk`, the "Recognizing…" popup, `applyAction` / `describeEdit` / `applyEdit`, and
  owns every failure dialog (all core strings — `objects_*` in `strings.xml`); `ObjectRenderPass` groups
  a page's objects by provider identity → `renderAll` → decode → the screen caches, sizes each object to
  its image and draws one pen-idle frame. The provider never sees an object id, page id, notebook name,
  or anything beyond its own payload + geometry + the two proxies. Absent provider / failed render →
  the dashed placeholder; failed create → the ink is untouched; failed apply / edit → the payload is
  untouched (rule 23 lived).
- **Contents (arc 5 / C1 — `docs/notebook.md` §"Contents (arc 5)"):** the notebook screen's
  `ContentsFlow` → `ContentsSource.gather` (one `describeOutlineAll` bind per outline-capable provider,
  payloads batched per type and chunked by `OutlineCaps`; drains the writer first; the candidate rows
  are sorted into document order and capped at `MAX_OUTLINE_ENTRIES` **before** the bind, and the bind
  is budgeted at ≤ 10 chunks — C2) → `OutlineTree` →
  the core-drawn `ContentsDialog` (sidebar ≥ 480 dp / full screen below). Entry points: the top-bar
  `list` button — **present only while `ObjectProviders.hasOutline` and the notebook holds an object
  of an outline-capable provider** (`ContentsSource.available`, re-evaluated after every provider
  load / page change / object mutation, so resume shows / hides it) — and the one-finger swipe-down
  (silent otherwise). A capable provider that fails the outline call → the `objects_provider_failed` dialog
  names it and nothing opens; an absent / disabled / pre-method provider simply contributes nothing.
  While the Contents shows the whole paper is excluded from the pen. The provider sees only its own
  payloads; the core keeps ids, pages, geometry; labels are never logged on either side.
- **Timings (H3, warm extension processes, two hops end to end):** `render` 268 ms (MIP11) / 479 ms
  (SNN) / 403 ms (NA5C) — of which the Markdown extension's own render is 10 / 93 / 28 ms;
  `createFromInk` 1.6 s (MIP11, first inference after process start) / 201 ms (SNN) / 58 ms (NA5C).

**BOOX sideload trap (NA5C):** the launcher/firmware flips a freshly installed sideloaded package to
DISABLED_USER shortly *after* `install`, so a `pm enable` issued immediately can be overwritten. Enable,
wait a few seconds, then confirm with `pm list packages -d` (must not list the extension). Discovery
correctly reports 0 candidates while it is disabled.

---

## Boundary audit (rows 1–9 E2, rows 10–13 N2 — walked 2026-08-16; rows 14–17 M2 + rows 1/6/7 re-walked for the shared `ExtensionBinder` 2026-08-17; rows 18–24 H5 + rows 1/6/7 re-walked for the two new clients and the proxies' inner calls 2026-08-18; rows 25–27 C2 + rows 1/6/7 re-walked for the appended `describeOutline` / `supportsOutline` calls 2026-08-18; rows 28–32 S3 + rows 1/6/7 re-walked for `ExtensionBinder.hold` / `HeldBinding.call` and the four `IScratchPad` calls 2026-08-19 — all ✅)

What crosses the process boundary, in which direction, and what guards it. Re-walk this table
whenever an extension point is added or a contract field changes.

**M2 re-walk of rows 1, 6, 7 (one implementation for three clients):** since M1 the "Where it holds"
column for the bind-time signature check, unbind-in-`finally` and the per-call timeout is
`ExtensionBinder.call` — `TemplateProviderClient`, `NamerClient` and `RecognizerClient` all go
through it and none keeps a private bind path. Walked against the code: `checkSignatures` runs
immediately before `bindService`; `unbindService` + the supervisor scope's cancel sit in the one
`finally` (bind refused / bind timeout / call timeout / exception / success); every client passes an
explicit `callTimeoutMs` (there is no default) and the bind wait is `BIND_TIMEOUT_MS` (3 s). The
namer's per-bind store binder is revoked in the client's own `finally` right after the shared unbind.

**H5 re-walk of rows 1, 6, 7 (two more clients + the proxies' inner calls):** `MarkdownClient.render`
and every `ObjectProviderClient` method go through the same `ExtensionBinder.call` (signature re-check
immediately before `bindService`, unbind + scope cancel in the one `finally`, explicit
`callTimeoutMs` on every call — 5 s / 2 s / 15 s / 10 s / 10 s × n). The two proxies add **no bind path
of their own**: `RecognizerProxyBinder` forwards through `RecognizerClient` and `MarkdownProxyBinder`
through `MarkdownClient`, so the inner hop of every proxied call re-runs row 1's signature check, gets
row 6's `finally` unbind and row 7's timeout on its own — and both proxies are minted after
`InkCaps.check` / `RenderCaps.checkArgs` and revoked in the consumer client's own `finally`, right after
the shared unbind (`createFromInk` / `render` / `renderAll`). Regions: `RenderedImages.copyOut` unmaps +
closes in nested `finally`; the proxy's re-wrapped region and the Markdown extension's own region are
closed in their `onTransact` `finally`; and (H5 fix) the Heading extension closes *its* handle on the
proxy's region the same way once its reply is marshalled — before that it was left to GC.

**C2 re-walk of rows 1, 6, 7 (the appended method — two more client calls, no new bind path):**
`ObjectProviderClient.describeOutlineAll` (what the Contents gather calls; `describeOutline` is a
one-type wrapper over it) and `supportsOutline` (the load probe) both go through the same private
`call(timeoutMs)` → `ExtensionBinder.call` as every arc-4 method: row 1's `checkSignatures` immediately
before `bindService`, row 6's unbind + scope cancel in the one `finally`, row 7's explicit timeout —
`CALL_TIMEOUT_MS` (2 s) for the probe, `2 s × chunks` for the gather (one bind, the chunks called in
sequence inside it; a chunk that outlives the budget is discarded with the whole bind, never joined).
Neither call mints a proxy or opens a region — the reply is a typed parcelable list, so there is
nothing to close. Verified on device across C0 / C1 (three devices): one `describeOutline` bind per
Contents open, binds = unbinds, no `leaked ServiceConnection`, no `SecurityException`.

**S3 re-walk of rows 1, 6, 7 (the first *held* bind — one bind path, four calls):**
`ExtensionBinder.hold` is `ExtensionBinder.call`'s bind half and nothing else: `checkSignatures`
immediately before `bindService` (row 1), an explicit `ComponentName` from the `ProviderRef`,
`BIND_AUTO_CREATE` on the app context, the connection awaited ≤ `BIND_TIMEOUT_MS` (3 s); on every
failure inside `hold` the attempted bind is released before the `ExtensionCallException` is thrown.
What differs is *when* the unbind runs — the operation is the showing, so row 6's `finally` is
`ScratchPadClient.finish` (`end()` in a `try`, `HeldBinding.close()` = supervisor-scope cancel +
`unbindService`, **and** `ExtensionStoreBinder.revoke()` in the one `finally`), called from every path:
the launcher's result (any code — `ScratchPadFlow.onResult` / `ScratchPadLaunch`), the launcher's
cancel, a failed `begin` / `send` inside `open` (before any launch), the caller's `onDestroy` while the
screen is up (`ScratchPadFlow.close` / `ScratchPadLaunch.close` on `appScope` — `NonCancellable`, outlives
the Activity), and a `CancellationException` mid-`open`. Row 7: every one of the four calls goes through
`HeldBinding.call(timeoutMs)` — `begin` / `receiveInk` (per chunk) / `takeOutgoing` (per chunk) / `end`
each ≤ `CALL_TIMEOUT_MS` (2 s), run on the binding's own IO scope and discarded when they outlive it;
after `onBindingDied` / `onServiceDisconnected` / `close` every call throws at once (`isDead`), so a
pad whose process died mid-showing cannot hang the host. Verified on device across S0–S2 (three
devices): one `hold` / one `unbind (held)` per showing, binds = unbinds, no `leaked ServiceConnection`,
`am start` of the screen refused.

| # | Invariant | Where it holds |
|---|---|---|
| 1 | **Host-side signature check on every discovery — and again at every bind.** No candidate is used unless exported, `META_API_VERSION == API_VERSION`, and `checkSignatures(core, ext) == SIGNATURE_MATCH`. Discovery is the only way a `ProviderRef` is made; every bind uses an explicit `ComponentName` from a `ProviderRef` and re-runs `checkSignatures` first (no TOCTOU window across the screen's lifetime). | `ExtensionRegistry.discover` (each rejection a `Slog.d`); `TemplateProviderClient.call` |
| 2 | **Extension-side caller check in every stub method.** `listTemplates` and `render` both call `CallerCheck.enforce` first: caller uid → packages must contain `BuildConfig.HOST_PACKAGE` **and** `checkSignatures(uid, myUid) == SIGNATURE_MATCH`, else `SecurityException`. The service has no other entry point (`onBind` only returns the stub). | `TemplateProviderService`, `CallerCheck` |
| 3 | **Nothing but geometry crosses outward.** `listTemplates()` carries no arguments; `render` carries a template id the extension itself issued, `widthPx`, `heightPx`, `dpi`. No passphrase, key, file path, index row, notebook id, page id, name, or stroke ever reaches the extension — the client API has no parameter that could carry one. | `ITemplateProvider.aidl`, `TemplateProviderClient.list/render`, `NewNotebookActivity.attemptCreate` (`pageWidthPx/pageHeightPx/dpi` only) |
| 4 | **Nothing but WEBP bytes (and id/name strings) crosses inward.** `TemplateInfo(id, name)` is used only to build radios and the `<pkg>:<id>` label; `RenderedTemplate` is reduced to a `ByteArray` in `copyOut` — the `SharedMemory` never leaves the client. | `TemplateProviderClient.copyOut`, `TemplateChoice` |
| 5 | **Byte cap + bounded decode on the way in.** `mimeType == MIME_WEBP`, `0 < byteCount ≤ min(MAX_RENDER_BYTES, memory.size)`, then `Bitmaps.imageSize` (header probe) must equal the requested `widthPx × heightPx` before the bytes are handed to the caller. The stored blob is later decoded only through `Bitmaps.decodeBounded(…, MAX_TEMPLATE_EDGE)` at open. | `TemplateProviderClient.copyOut`, `NotebookSession.loadTemplateFor` |
| 6 | **Every bind has an unbind in `finally`; the region is always closed.** `call()` unbinds in `finally` on every path (bind refused, bind timeout, call timeout, exception, success); `copyOut` unmaps and closes in nested `finally`. Extension side: the region is closed in `onTransact`'s `finally` after the reply is written. Verified on device: one bind/unbind pair per list and per render, no `leaked ServiceConnection`. | `TemplateProviderClient.call/copyOut`, `TemplateProviderService.onTransact` |
| 7 | **Every call has a timeout.** Bind ≤ 3 s, list ≤ 2 s, render ≤ 15 s; an un-interruptible Binder call that outlives its timeout finishes on its own supervisor-scope IO thread and is discarded — the caller never hangs. | `TemplateProviderClient` constants + `call()` |
| 8 | **Failure never creates a notebook silently different from what the user chose.** Render runs **before** any file exists; a failed / null / empty / undecodable / wrong-size render → toast + stay on the screen; Blank is only ever the user's own selection. No extension → no Template section, and the notebook created is the Blank the user saw. A recreated screen (keyboard attach on Ratta, locale) saves the chosen identity and re-checks that radio once discovery rebuilds the list — Blank is re-checked only if nothing was chosen or the template is no longer offered. | `NewNotebookActivity.attemptCreate` / `onSaveInstanceState`, `docs/library.md` §New notebook |
| 9 | **The core has no renderer and no dependency on the extension.** `:app` depends on `:extension-api` only; the template WEBP is drawn from the `.soil` blob exactly as v0 drew it, so a notebook opens with its template whether or not the extension is installed. | `app/build.gradle.kts`, `NotebookSession.loadTemplateFor` |
| 10 | **Outward payload of NotebookNamer is exactly folder UUID + sibling notebook names (+ the scheme text the user typed).** `describeField()` and `validateScheme(scheme)` carry nothing else; `currentScheme` / `saveScheme` / `defaultName` carry the folder UUID (a random id — the store key, no content) and `defaultName` alone adds the names of the folder's own notebooks (needed only for `{n}`). No other argument exists in `INotebookNamer` — no passphrase, key, path, index row, other folder, page or stroke can be carried. This is the **recorded widening of row 3** for this point only. | `INotebookNamer.aidl`, `NamerClient` (five methods), `LibraryActivity.launchNewNotebook` (`siblings` = the current listing's `CardItem.Notebook` names) |
| 11 | **The store binder is uid-bound, per-bind, revocable, capped.** Minted only inside `NamerClient.call(store = true)` — after `ExtensionStores.open` on IO (pre-open rule) and with `extUid = getPackageUid(ref.packageName)` fetched at bind time; `ExtensionStoreGate.check()` requires `getCallingUid() == extUid && !revoked` on **every** method; `revoke()` runs in the same `finally` as the unbind, so a late call from an orphaned (timed-out) transaction fails closed. Caps host-side: key `1..512` chars, value `≤ 256 KiB` (raised to 4 MiB with the large path in arc 6 — row 32), new key at `≥ 50 000` → `IllegalStateException`. The DB is opened only through `SoilCrypto` factories under the global key (`ExtensionStores.open`, the third named create entry point); `IExtensionStore` has no method that could return a key, path, or `File`. | `NamerClient.call`, `ExtensionStoreBinder`, `ExtensionStoreGate` (JVM-tested: uid mismatch, revoked, caps, literal case-sensitive prefix, DAO failure → `IllegalStateException`), `ExtensionStores.open`, `IExtensionStore.aidl` |
| 12 | **Inward payload is validated.** `SchemeField` strings are truncated (`40 / 60 / 200`) and drawn only as a caption, an `EditText` hint and a help line; a `currentScheme` is capped at `MAX_NAME_CHARS` and shown verbatim only **inside** a text field; a validation error is truncated (`200`) and shown only as the message of a core-owned problem dialog; a `defaultName` is accepted only if `NewNotebookActivity.acceptDefaultName` says so (core name rule **and** `≤ MAX_NAME_CHARS`) — else the core default, silently (`Slog.d`, never a toast, never a crash). Any exception, timeout, or null on the way in becomes `ExtensionCallException` at the client and a core-owned outcome at the entry point. | `NamerClient` (`MAX_LABEL/HINT/HELP/ERROR`, `take(MAX_NAME_CHARS)`), `SchemeDialogs.buildField`, `NewNotebookActivity.acceptDefaultName` |
| 13 | **Failure never changes what the user chose.** +Notebook: namer failure / null / timeout / root → `NewNotebookActivity` opens with the core default (the tap just takes a beat; a second tap during it is dropped). New folder: `describeField` failure → the dialog without the field; the scheme is validated **before** the folder exists (error → problem dialog + stay); the folder is created **before** its scheme is saved and a save failure says so (`naming_save_failed`, a problem dialog) while the folder stands. Long-press: fetch failure → `naming_unavailable` problem dialog and no scheme dialog; save/validate failure inside → problem dialog, the scheme dialog stays with the text. (M2: every one of these was a toast in N1; swept to `Dialogs.problem` under the toast-vs-dialog rule.) The extension absent / disabled → all three entry points vanish (`namerRef == null`) and nothing else changes; its store `.db` survives so the schemes return with it. | `LibraryActivity.launchNewNotebook` / `showNewFolderDialog` (both overloads) / `openSchemeDialog`, `SchemeDialogs.showSchemeDialog`, `LibraryActivity.refreshNamer` |
| 14 | **Outward payload of HandwritingRecognizer is bare geometry only.** `InkStroke` carries two parallel `FloatArray`s (x/y in the caller's px) and nothing else — its parcel form is `int n · float[] x · float[] y`; `InkPayload.fromStrokes` is the **one** reduction site from the paper's `Stroke` (id, colour, width, style, pressure, tilt, time never leave; point-less strokes are skipped). The other arguments are the writing-area / page size and, for `recognizeInk`, ≤ `MAX_PRECONTEXT_CHARS` (20) of pre-context (`InkCaps.preContext` = `takeLast`). No other argument exists in `IHandwritingRecognizer` — no notebook / page id, name, key or path can be carried. Recorded as the explicit widening of row 3: **the first point that receives ink.** The debug menu passes the session's page px size and `paper.getStrokes()` through `RecognizeContext` — no ids, no names, no session. | `IHandwritingRecognizer.aidl`, `InkStroke` (parcel), `InkPayload.fromStrokes` (JVM-tested: id/colour/pressure dropped, x/y preserved), `RecognizeContext`, `RecognizerClient.recognizeInk/recognizePage`, `InkCaps.preContext` |
| 15 | **Ink is capped before it crosses and re-checked after.** Host side, **without binding**: `InkCaps.check` requires `strokes.size ≤ MAX_INK_STROKES` (2 000), `Σ points ≤ MAX_INK_POINTS` (60 000), every stroke non-empty with equal-length x/y arrays, and `width`/`height` `> 0` (NaN fails the comparison) — any violation is `InkTooLargeException` (an `ExtensionCallException`) thrown before `ExtensionBinder.call`; `InkStroke`'s constructor independently rejects empty / mismatched arrays. Extension side: `InkStroke.CREATOR` re-runs those `require`s at unmarshal time and `HandwritingRecognizerService.checkInk` re-checks the two caps + positive sizes → `IllegalArgumentException` (Binder-marshalable). | `InkCaps.check` (JVM-tested: over strokes / over points / empty stroke / mismatched arrays / non-positive size / preContext truncation), `RecognizerClient`, `InkStroke.init` + `CREATOR`, `HandwritingRecognizerService.checkInk` |
| 16 | **Inward payload is validated; nothing is stored.** `InkCaps.status` maps anything outside `READY..UNAVAILABLE` (`0..3`) to `UNAVAILABLE`; `InkCaps.text` = `?: ""` then `take(MAX_RECOGNIZED_CHARS)` (20 000). The extension's `IllegalStateException` is typed `RecognizerNotReadyException` only when its message equals `ExtensionContract.RECOGNIZER_NOT_READY`, else a generic `ExtensionCallException` — either way the caller sees a core-owned dialog. The recognized text is shown **only** in the debug result dialog (selectable + Copy to the clipboard, at the user's tap) and is written nowhere: no `page_text`, no `.soil` / index change, no log line on either side (counts + durations only — `RecognizerClient` and the service log `N strokes → M chars in T ms`). | `InkCaps.status/text` (JVM-tested), `RecognizerClient.call`, `NotebookDebugMenu.showResult`, the two log lines in `RecognizerClient` / `HandwritingRecognizerService` |
| 17 | **Failure changes nothing.** Extension absent / disabled → `ExtensionRegistry.handwritingRecognizer == null` → the debug sheet has no Recognize item and nothing else on the screen changes. Not ready / offline / timeout / too dense / engine failure → a core-owned dialog (`recognize_*` strings) and the flow ends; the page, its ink, the session and every other extension are untouched (the debug menu only *reads* `paper.getStrokes()` and the page size; it holds no `.soil` handle). The model and the model-present flag are the extension's own state (its sandbox, ML Kit-managed): they survive `pm disable-user` / `pm enable` (M1 checklist #9 — no re-download) and `am force-stop` (checklist #8 — auto-create, READY at once via the flag), and are removed only by the extension's uninstall. A `RecognizerNotReadyException` after READY was reported (extension restarted mid-flow) is a dialog, not a retry loop; one activity-owned busy guard drops a second tap for the whole flow. **Nothing downloads without consent:** only `prepare()` starts the chain, and the host calls it only from the Download button. | `ExtensionRegistry.handwritingRecognizer`, `NotebookDebugMenu` (sheet build, `recognizeBusy`, every failure branch), `ModelManager` (prefs flag, `onEngineFailure`), `HandwritingRecognizerService.onCreate` |
| 18 | **Outward payload of `MarkdownRenderer` is markdown text + layout numbers only.** `render(markdown, maxWidthPx, dpi, maxLines, paddingPx)` — the source truncated to `MAX_MARKDOWN_CHARS`, `maxWidthPx` in `1..MAX_IMAGE_EDGE_PX`, finite `dpi > 0`, `maxLines ≥ 0`, padding in `0..RENDER_PADDING_MAX_PX`, all checked **before the bind** (`RenderArgsException` never binds). There is no other argument in `IMarkdownRenderer` — no id, name, key or path can be carried. Today the only source that reaches it is an object payload the Heading extension chose to forward (see row 19); the core itself renders nothing through it. | `IMarkdownRenderer.aidl`, `MarkdownClient.render`, `RenderCaps.markdown/checkArgs` (JVM-tested), `MarkdownProxyBinder.render` (same caps re-applied inward) |
| 19 | **Outward payload of `ObjectProvider` is the object's own payload + geometry + the two proxies.** Every method carries at most: an action id / typeId **the provider itself declared**, the object's payload (`outPayload` = cut to `MAX_OBJECT_TEXT_CHARS`), edit text (cut to `MAX_EDIT_TEXT_CHARS`), `maxWidthPx` + `dpi`, and — `createFromInk` only — **bare ink** (`InkPayload.fromStrokes`: x/y only, page px, writing order; `InkCaps.check` before the bind) + the selection bounds' size (row 14's widening re-recorded: the second point that receives ink), plus the null-or-proxy `IHandwritingRecognizer` / `IMarkdownRenderer`. `PageObject.id`, page id, notebook id / name, order, the `.soil` path, keys — none has a parameter to travel in; `RenderRequest` is (typeId, payload, maxWidthPx). Host-side logs name object ids (`ObjectStore`) but never cross. | `IObjectProvider.aidl`, `ObjectProviderClient` (every method), `ObjectActions.perform/editTapped`, `ObjectRenderPass` (`RenderRequest`), `InkPayload.fromStrokes` |
| 20 | **Both proxies are uid-bound, per-bind, revocable, capped.** `ProxyGate(extUid, Binder::getCallingUid)` — `extUid` from `PackageManager.getPackageUid(ref.packageName)` at mint time; every proxied method calls `gate.check()` first (`SecurityException` on another uid or after `revoke()`). Minted inside `createFromInk` / `render` / `renderAll` **after** the outward caps, revoked in that call's `finally` right after the shared unbind — a late transaction from an orphaned reference fails closed. Caps re-applied inward before forwarding: `InkCaps.check` + `preContext` truncation (`RecognizerProxyBinder`), `RenderCaps.checkArgs` + source truncation (`MarkdownProxyBinder` → `MarkdownClient.render`). Forwarding = the core's own clients on the Binder thread (`runBlocking`, never Main) with their own bind / timeout / signature check; the two-hop budget lives inside the consumer call's timeout with margin (15 s ⊃ 3 + 10 s, 10 s ⊃ 3 + 5 s — H5 raised `render` from 8 s, which had none). Failures leave as the marshalable set only. | `ProxyGate` (JVM-tested: uid mismatch, revoked, happy path), `RecognizerProxyBinder`, `MarkdownProxyBinder`, `ObjectProviderClient.createFromInk/render/renderAll` (`finally { proxy?.revoke() }`) |
| 21 | **Inward payloads are validated.** `describeTypes` → `isTypeId` filter + `MAX_TYPES`; `describeActions` → `ActionCaps.sanitize` (ids re-validated + unique, labels trimmed/truncated, `appliesTo` / `requires` masked, icons only through `IconCatalog` by name, sub-actions capped, one level); `activeActionIds` → id pattern + cap; `CreatedObject` → `requireValid` at unmarshal, **typeId ∈ the same bind's `describeTypes()`**, payload cut to `MAX_OBJECT_TEXT_CHARS`; `applyAction` / `applyEdit` → null/blank = no change, else cut; `describeEdit` → `EditSpec.require`s at unmarshal + `EditCaps.sanitize`; `RenderedImage` → `requireValid` at unmarshal, then `RenderedImages.copyOut`: mime == `MIME_WEBP`, `0 < byteCount ≤ min(MAX_RENDER_BYTES, region)`, **header size == declared** and ≤ `MAX_IMAGE_EDGE_PX` (`RenderCaps.imageProblem`), region always closed; the verified bytes are decoded bounded on the host. The `RenderedImage` a *proxy* hands a provider is re-wrapped from bytes the host already verified, so a provider that returns it as-is cannot smuggle anything the host did not check. | `ObjectProviderClient` (every method), `ActionCaps` / `EditCaps` / `RenderCaps` (JVM-tested), `CreatedObject.requireValid`, `RenderedImage.requireValid`, `RenderedImages.copyOut/wrap`, `ObjectRenderPass` decode |
| 22 | **The core stores objects, never renders / parses them; nothing but the payload the provider chose is stored.** An object row is identity + `<pkg>:<typeId>` + the opaque `text` + `x y width height` + `order` (`docs/data.md` §Object rows); the core never reads inside `text` (no `#` counting, no markdown), never persists a bitmap (`ObjectRenderCache` is in-memory, per open session, keyed by id + payload + width + dpi), and stores the recognizer's output only *as* the payload the provider returned from `createFromInk` (rule 16 amended: the payload is the object, not a cached capability result). Width/height are the rendered image's after a successful render — geometry, not content. Payloads are never logged on either side (counts / lengths / durations only). | `ObjectStore`, `ObjectRows`, `PageObject`, `ObjectRenderCache`, `ObjectRenderPass`, `ObjectActions` log lines, `ObjectProviderService` / `MarkdownRendererService` log lines |
| 23 | **Absent provider = placeholder; failure changes nothing.** No provider for an identity, provider disabled, `CapabilityRequiredException` from a render, a failed / null / undecodable render → the object draws as the **dashed placeholder at its bounds**, still selectable / movable / deletable, and re-renders when the provider is back (resume signature → reload → pass). Failed / null `createFromInk` (unreadable, not ready, timeout, capability missing, page cap) → **the ink is untouched and nothing is created**; failed / null `applyAction` / `applyEdit` → the payload is untouched; every failure is a core-owned dialog (`objects_*` strings) or a placeholder — the extension shows nothing. The `requires` guards name the missing capability before any bind; `ObjectActions.busy` drops a second tap for the flow. | `ObjectRenderPass`, `ObjectRenderer` (placeholder), `ObjectActions` (guards, every catch → dialog), `NotebookActivity.objectListener` (strokes must still be live before create + erase), `ObjectProviders.load` (skip on failure) |
| 24 | **Contributed UI is drawn only by the core.** An extension contributes *descriptions* — `SelectionAction` (id, ≤ 6-char label, catalog icon **name**, sub-actions, applies-to, requires) and `EditSpec` (title, prefill, hint, maxChars, multiLine) — and the core draws every button, sub-toolbar and dialog under its own rules (`SelectionToolbar` + `ToolbarAnchor`, `ObjectEditDialog`, `IconCatalog` Tabler drawables, dimen-driven tap targets, exclusion rects, `releaseRender`, no colour). No extension pixel, layout, `RemoteViews`, `SurfaceControlViewHost` surface or code ever reaches the notebook screen; the only extension-made pixels are the rendered *object image*, which the host verifies (row 21) and draws itself through g-paper's `ContentRenderer`. | `SelectionAction` / `EditSpec` (parcel), `ActionCaps`, `IconCatalog`, `SelectionToolbar`, `ObjectEditDialog`, `ObjectRenderer`, §"Selection-toolbar contributions" tier rule |
| 25 | **Outward payload of `describeOutline` is the provider's own payloads, grouped by type, chunked.** `describeOutline(typeId, payloads)` carries a typeId **the provider itself declared** and a list of the opaque payloads it produced (each through the same `outPayload` cut to `MAX_OBJECT_TEXT_CHARS` as every arc-4 method), chunked by `OutlineCaps.chunk` (≤ `MAX_OUTLINE_BATCH` items / ≤ `MAX_OUTLINE_BATCH_CHARS` summed per call). There is no other parameter — object ids, page ids / numbers / count, `x y width height`, `order`, notebook id / name, keys, paths cannot travel: `ContentsSource.gather` keeps `pageIndex` / `x` / `y` beside each row on the host and re-joins them to the reply by list position. The probe sends exactly one blank payload. The provider sees text it wrote and nothing about *where* it lives. | `IObjectProvider.aidl` (`describeOutline`), `ObjectProviderClient.describeOutlineAll` (`plan` = `byType.mapValues { chunk(map(::outPayload)) }`), `OutlineCaps.chunk` (JVM-tested by count + chars + over-long payload), `ContentsSource.gather` (`byProvider` map, `pageIndex` join), `ObjectProviderClient.supportsOutline` (`listOf("")`) |
| 26 | **Inward outline replies are validated; a pre-method provider is "not capable", never an error.** `OutlineEntry.requireValid` at unmarshal (level `0..MAX_OUTLINE_LEVEL`, label ≤ `MAX_OUTLINE_LABEL_CHARS` — a violation aborts the whole reply at the Binder layer); then `OutlineCaps.sanitize(reply, expected)`: **null / any length ≠ the chunk's input length → null** (this provider does not answer the outline for this call — the whole `describeOutlineAll` becomes null, `ExtensionCallException` inside, logged as counts), else per entry the label is trimmed and cut to the cap, a blank label with level ≥ 1 → level 0, a level outside `0..MAX_OUTLINE_LEVEL` → 0, a null element → level 0. Nothing else is trusted; the host never sorts, nests or draws from anything but these normalised (label, level) pairs and its own row geometry. The load probe (`supportsOutline` — `OutlineCaps.isCapableReply`: exactly one entry) turns the **empty reply an arc-4 provider's `onTransact` produces for the unknown transaction** (not an exception — §"Versioning rules") into `Contribution.outline = false`, logged `outline probe: unsupported`, outside the resume signature; no further outline call is ever made to that provider. Labels are never logged on either side (counts + durations only: `describeOutlineAll: n type(s), n payload(s), n call(s) → n entries in ms`; the extension logs `describeOutline n=<count> in <ms>`). | `OutlineEntry.requireValid` + `CREATOR` (JVM-tested: level 7 / over-long label rejected, round trip), `OutlineCaps.sanitize/isCapableReply` (JVM-tested: wrong length → null, blank → 0, clamp, trim/cut), `ObjectProviderClient.describeOutlineAll/supportsOutline`, `ObjectProviders.load` (`outline=` per provider), verified against the arc-4 Heading APK on MIP11 (C0) |
| 27 | **The Contents is core-drawn from descriptions; absent / failed provider = its objects are not listed / the screen does not open with an honest dialog; nothing on the page changes.** The Contents screen is a core `Dialog` built by the core from (label, level, pageIndex) triples under its own rules — `OutlineTree` sorts (page, y, x), nests (orphans attach to the nearest shallower heading), pages and highlights; `ContentsDialog` draws every row, toggle, page number, pager and the width rule (`ContentsLayout`) with Tabler icons and dimen-driven tap targets; no extension pixel, layout or code reaches it (rule 20 / row 24 re-lived). Provider absent / disabled / not outline-capable → its objects are simply not listed and neither entry point exists for a notebook whose only objects are its (`ContentsSource.available` = a live object of a *capable* provider on a live page — button `GONE`, swipe silent, `open()` refuses). A **capable** provider whose outline call fails (null / wrong length / timeout / exception) → `Result.Failed(label)` → the core's `objects_provider_failed` dialog under the "Contents" title and **nothing opens** (C0 Q4 — never a half list that looks complete). An empty gather (a race with a delete) opens nothing and re-refreshes availability. In every branch the page, its ink and objects, the session and every other extension are untouched: the gather is read-only (`writer.drain()` + `liveObjectsAll()`), a row tap only calls the host's existing `navigateTo`, the dialog holds no `.soil` handle, and while it shows the whole paper is one exclusion rect (`NotebookChrome.blockAll`) so a stylus cannot ink through it. Rebuilt on every open — nothing is cached or persisted (no prefs, no rows, no store). | `ContentsSource.available/gather` (`Result.Failed` stop, `Result.Ok` cap), `ContentsFlow.open/refresh` (busy guard, `Dialogs.problem(contents_title, objects_provider_failed)`, `showing` → `onShowingChanged`), `ContentsDialog` + `ContentsLayout` (JVM-tested width / rows-per-page / indent), `OutlineTree` (JVM-tested nest / orphan / highlight / paging), `NotebookChrome` (`blockAll`), `NotebookActivity` (`btnContents` visibility via `contentsFlow.refresh()` after `loadProviders` / `navigateTo` / create / delete; `onSwipeDown` → `open()`) |
| 28 | **Outward on `begin` is the uid-bound store binder only.** `begin(store)` is the one argument of the held bind's opening call: the same `ExtensionStoreBinder` as rows 10–13 (minted in `ScratchPadClient.open` **after** `ExtensionStores.open` on IO — pre-open rule — with `extUid = getPackageUid(ref.packageName)` at bind time; `ExtensionStoreGate.check()` on every method; `IExtensionStore` still has no method that could return a key, path or `File`), now **held for a showing** in the extension's `ScratchSession.store` and revoked in the same `finally` as the unbind — every path: result, cancel, caller `onDestroy`, failed `open` (S3 re-walk above). Nothing else reaches the extension at open: the screen Intent carries `ACTION_SCRATCH_PAD_SCREEN` + `setPackage` + the two recorded booleans (`EXTRA_SCRATCH_SEND_ENABLED`, `EXTRA_SCRATCH_OPEN_RECEIVED`) — no key, path, name, notebook / page id. A second `begin` while one is held replaces the binder (the host restarted); `end` clears it; a dead binder (`SecurityException` after revoke / `DeadObjectException` after the host's death) is `StoreUnavailable` → the pad's honest dialog + `finish`. | `ScratchPadClient.open/finish`, `ExtensionBinder.hold` / `HeldBinding`, `ExtensionStoreBinder`, `ExtensionStoreGate` (JVM-tested), `ScratchPadService.begin/end`, `ScratchSession`, `ScratchStore.guard` (→ `StoreUnavailable`), `ScratchPadActivity` (store-unavailable dialog) |
| 29 | **Outward ink (`receiveInk`) is bare stroke geometry + style + the page px size — capped and chunked before the bind.** `InkBundle(strokes, pageWidth, pageHeight)` with `PaperStroke` = four parallel `FloatArray`s (x / y / pressure / tilt in the authoring page's px) + `width` + `colorArgb` + the `StrokeStyle` **name**; `TransferCaps.toPaperStrokes` is the one reduction site from the paper's `Stroke` (id and time never leave; point-less strokes skipped) and `placement` is one of two recorded ints. No stroke id, page id / number, notebook id / name, selection bounds or position beyond the strokes' own coordinates has a parameter to travel in — `IScratchPad` has no other argument (the third recorded widening of row 3 after rows 14 / 19: the third point that receives ink). Host side, **before any bind**: `TransferCaps.withinLimits` (≤ `MAX_TRANSFER_STROKES` 10 000 / `MAX_TRANSFER_POINTS` 400 000) in `ScratchPadFlow.startSend` → `scratch_too_large`; then `TransferCaps.chunk` = `InkChunks.chunk` (≤ `TRANSFER_CHUNK_STROKES` 300 / `TRANSFER_CHUNK_POINTS` 20 000 per bundle — `InkBundle.requireValid` rejects a bigger one at construction), one `receiveInk(bundle, placement, last)` per chunk ≤ 2 s on the held bind. Extension side: `PaperStroke.requireValid` + `InkBundle.requireValid` re-run at unmarshal (a malformed stroke rejects the whole bundle at the Binder layer), the running totals are re-checked against the two caps under `synchronized(ScratchSession)` (over → inbound dropped, `IllegalArgumentException`), the placement int is checked, fresh ids are minted (`ScratchInk.toStrokes`: unknown style → PEN, width clamped), and the page is written **on the Binder thread** by `ScratchStore.receive` under the page-full rule. | `IScratchPad.aidl` (`receiveInk`), `PaperStroke` / `InkBundle` (parcel + `requireValid`, JVM-tested), `TransferCaps.withinLimits/chunk/toPaperStrokes` (JVM-tested), `InkChunks` (JVM-tested), `ScratchPadFlow.startSend`, `ScratchPadClient.send`, `ScratchPadService.receiveInk`, `ScratchInk.toStrokes` (JVM-tested), `ScratchStore.receive` |
| 30 | **Inward ink (`takeOutgoing`) is validated; the paste is one undoable step and nothing else on the page changes.** Every reply is an `InkBundle` → `requireValid` at unmarshal (chunk caps, equal channel lengths, finite `width > 0`, finite positive page size), then `TransferCaps.sanitize` (known style or PEN, width in `MIN_WIDTH`..`MAX_WIDTH` — NaN → default, opaque black — **no colour crosses in**) under `TransferCaps.Drain`: stop at the first empty bundle, at the summed caps (`MAX_TRANSFER_STROKES` / `MAX_TRANSFER_POINTS` — the rest dropped, `truncated`), or at `TRANSFER_MAX_CHUNKS` (34) + one probe past the budget (a non-empty chunk there = `truncated` → the core's `scratch_truncated` dialog names the pasted count). Fresh ids minted by the core (`TransferCaps.toStrokes`, `timeMillis 0`); `NotebookActivity.pasteStrokes` inserts the rows after the page's last stroke (`StrokeStore.insert` on the serial writer), adds them to the paper, records **one** `NotebookUndo.Action.Pasted` (undo removes exactly those rows; redo restores them in place), switches to the lasso and leaves them selected **1:1** (host-initiated `setSelection`, no echo) — no other row, object, page or session state is touched; a failed drain → `scratch_failed`, nothing pasted. The pad's side is symmetric: a received placement lands as one `Pasted` (or one `Page` step for a New-page placement) on its own stack. | `IScratchPad.aidl` (`takeOutgoing`), `InkBundle.requireValid` (JVM-tested), `TransferCaps.sanitize/toStrokes/Drain` (JVM-tested: stop on empty / summed caps / chunk budget + probe / NaN width / colour forced), `ScratchPadClient.drainOutgoing`, `ScratchPadFlow.onResult`, `NotebookActivity.pasteStrokes`, `NotebookUndo.Action.Pasted`, `StrokeStore.insert`, `ScratchPadActivity.selectReceived` |
| 31 | **The screen is the extension's, launched only by the core, caller-checked both ways; data never rides the Intent.** `ScratchPadActivity` is exported with a custom action (`ACTION_SCRATCH_PAD_SCREEN`) and **no launcher filter**; first thing in `onCreate` it calls `HostCallerCheck.enforceActivity` — `callingPackage == HOST_PACKAGE` **and** `checkSignatures(caller, self) == SIGNATURE_MATCH`, else `finish()` before anything is inflated (a plain `am start` has no caller → refused; verified on all three devices S0–S2). The core launches it only through an `ActivityResultLauncher<Intent>` (that is what sets `callingPackage`), with `setPackage(ref.packageName)` from a trusted `ProviderRef`, and only **after** `begin(store)` succeeded on the held bind and `paper.releaseForHandoff()` on a paper-hosting caller. The Intent carries the two recorded booleans and nothing else; the Activity reads only those two `EXTRA_*` and returns only `RESULT_SCRATCH_SEND` / `RESULT_CANCELED` — the ink in both directions goes through the held service (rows 29–30), the pages through the held store binder (row 28). Every exit back to the caller runs `releaseForHandoff()` before `finish()` (`finishWithHandoff`); `onResume` reclaims with `resumeDrawing()` (rule 27). | `ScratchPadActivity.onCreate` (`enforceActivity` first) / `finishWithHandoff` / `onResume`, `HostCallerCheck.enforceActivity`, `:ext-scratchpad` manifest (`exported`, custom action, no `LAUNCHER`), `ScratchPadClient.open` (Intent build), `ScratchPadFlow.launchPad` / `ScratchPadLaunch.open` (`ActivityResultLauncher`, `releaseForHandoff()` immediately before `launch`) |
| 32 | **The raised store cap changes no rule.** A value is `≤ STORE_MAX_VALUE_BYTES` (4 MiB): **inline** (`put` / `get`) up to `STORE_MAX_INLINE_BYTES` (512 KiB) — a `get` of a stored value above it throws `IllegalStateException(STORE_VALUE_LARGE)` ("use getLarge"), a `put` above it is refused; **large** (`putLarge` / `getLarge`) as a `LargeValue` = a read-only ashmem region + `byteCount` (`LargeValue.requireValid`: `0..STORE_MAX_VALUE_BYTES`, `byteCount ≤ region size`; an empty stored value rides a 1-byte region — `getLarge` returns any stored size) the receiver copies out of and closes in `finally` — host side `SharedBytes.readAndClose` **before** the gate sees bytes (so the cap applies to the copy, never to a live mapping), the ashmem step wrapped so a mapping failure is an `IllegalStateException` like every gate failure (never an empty reply the extension reads as null — S3), the reply region parked per Binder thread and closed in `onTransact`'s `finally`; extension side `readAndClose` in `ScratchStore.readPage`, its own put-region closed in `finally` after `putLarge` returns. Keys still `1..512` chars and `≤ STORE_MAX_KEYS` (50 000 — a *new* key past it refused); every method still uid-bound + revocable through the same `ExtensionStoreGate.check()`; the DB still opened only through `SoilCrypto` under the global key. The two appended methods follow the arc-5 compatible-change recipe (appended **last** in `IExtensionStore`, the arc-2 methods' codes untouched; `API_VERSION` still 1 — the host and its store binder are always the current build, the recipe only keeps an older extension's `put` / `get` working unchanged). **A page over the cap is refused by the extension, never split, never written elsewhere:** `ScratchStore.receive` / `ScratchDocument` measure the exact encoded size (`ScratchPageCodec`) and throw `PageFullException` → `SCRATCH_PAGE_FULL` on `receiveInk` (typed `ScratchPageFullException` → the core's `scratch_page_full_host` dialog, nothing placed) / the pad's own `scratch_page_full` dialog once per visit on a stroke the page cannot take (the stroke removed, nothing written); the pad has no file, prefs or second store of its own. | `ExtensionContract.STORE_*`, `IExtensionStore.aidl` (`putLarge` / `getLarge` appended), `LargeValue` (parcel + `requireValid`, JVM-tested), `SharedBytes.write/read/readAndClose`, `ExtensionStoreBinder.putLarge/getLarge/onTransact`, `ExtensionStoreGate` (JVM-tested: inline cap, large cap, `STORE_VALUE_LARGE`, key count), `ScratchStore.readPage/savePage/receive`, `ScratchDocument` (exact running size, `PageFullException`), `ScratchPadActivity` (page-full dialog once per visit) |

## Rules for adding a future extension point (write-once, follow later)

1. Add the action + AIDL + parcelables to `:extension-api`; keep the dependency direction.
2. Add discovery to `ExtensionRegistry` (same trust filter) and a client class with explicit timeouts,
   bind-per-operation, unbind-in-finally, and untrusted-payload caps.
3. The core decides what the user sees on failure; extensions never show UI in the core's flow.
4. Document the point here (contract + host behaviour + failure behaviour) and add its rows to the
   boundary audit.
5. Nothing crosses the boundary that the call doesn't need — never keys, files, index rows.

Followed by NotebookNamer (N1): `NOTEBOOK_NAMER` + `INotebookNamer` + `SchemeField` in
`:extension-api`; `ExtensionRegistry.notebookNamer` + `NamerClient`; the core owns every dialog / toast; the
one recorded widening of rule 5 is folder UUID + sibling notebook names; audit rows 10–13 (N2).

### Adding a data-holding point (arc 2 pattern)

A point whose extension must remember something between calls follows the five rules above **plus**:

6. **The extension keeps its data in the host-owned store, never in its own files.** Every AIDL method
   that may need it takes `IExtensionStore store` as an **in-parameter** — there is no reverse
   discovery, no exported host service, no store handle kept across calls.
7. **The client opens the store on IO before it binds** (`ExtensionStores.open(ctx, ref.packageName)`
   — a cold KDF must never sit inside the call timeout), mints **one** `ExtensionStoreBinder(db,
   extUid)` for that bind (`extUid` from `PackageManager.getPackageUid` at bind time), hands it to the
   call, and **revokes it in the same `finally` as the unbind**. Copy `NamerClient.call`.
8. **Only host-fixed data crosses outward** — the identity the store row hangs off (a UUID) and the
   minimum the call needs; record any widening of rule 5 in the audit, as row 10 does.
9. **Everything the extension returns from its store is untrusted on the way back** — cap, validate,
   and fall back to the core's own behaviour silently (row 12).
10. **A failure must leave the user's own choice intact** — create the core object first, save the
    extension's data second, and say so when the second step fails (row 13).
11. **Pre-warm at library resume** if the point is on a hot path (`LibraryActivity.refreshNamer` opens
    the namer's store on IO once the ref is known) — deferred: generalise to every discovered
    extension when Templates gains a store (`PAPER_NAMING_PLAN.md` §Deferred).

Followed by HandwritingRecognizer (M0–M2): `HANDWRITING_RECOGNIZER` + `IHandwritingRecognizer` +
`InkStroke` + `RecognizerStatus` in `:extension-api`; `ExtensionRegistry.handwritingRecognizer` +
`RecognizerClient` over the shared `ExtensionBinder`; every dialog is the debug menu's; the recorded
widening of rule 5 is bare x/y geometry + area/page size + ≤ 20 chars of pre-context; audit rows
14–17 (M2). It is stateless, so rules 6–11 do not apply; it is the first **capability point**, so:

### Adding a capability point (arc 3 pattern)

A point whose implementation the core lends to *other* extensions ("an extension for other
extensions") follows rules 1–5 **plus**:

12. **The core is the only binder of the provider.** Its client (`RecognizerClient`) is bind-per-
    operation over `ExtensionBinder` like every other; extensions never discover or bind each other,
    and the core never exports a service for them.
13. **The capability's AIDL is the interface both the provider and the core's future proxy implement**
    — no second contract type. Design the interface so it can be lent as-is: stateless, bare-geometry
    (or equally bare) arguments, plain results, `status()`/`prepare()` for anything the provider must
    acquire, and Binder-marshalable exceptions only (`SecurityException`, `IllegalArgumentException`,
    `IllegalStateException`).
14. **Caps run on the host before the bind and are re-checked by the provider** (`InkCaps` /
    `checkInk` — row 15). A consumer's proxy re-applies the same caps inward before forwarding.
15. **Engine assets may live in the provider's own sandbox** (the model + its "present" flag) — the
    recorded exception to rule 6: engine assets are not user data and exceed the store cap. User data
    still goes to the host store only.
16. **The core stores no result of the capability** (row 16) — a consumer that wants to keep results
    keeps them in *its* host store, through *its* point.
17. **Lend it only through the proxy recipe below** — built with the first consumer, never before.

### Adding an object point (arc 4 pattern)

A point whose extension puts **something that isn't ink on the page** — a content object the core
stores, positions, selects, moves, deletes and undoes but never interprets — and contributes UI to
the notebook screen, follows rules 1–5 (and 12–17 for any capability it consumes) **plus**:

18. **The core stores objects; providers never do.** An object is a core row (identity, geometry, an
    opaque payload) under its page; a provider keeps nothing about a specific object anywhere (its
    host store, if it has one, is for settings — the Heading has none). Audit row 22.
19. **The payload is opaque to the core** — never parsed, never logged, capped at
    `MAX_OBJECT_TEXT_CHARS`, shown only inside a provider-described edit dialog. Rows 19 / 22.
20. **The core draws every piece of contributed UI** under its own e-ink rules from a description
    (`SelectionAction`, `EditSpec`, catalog icons by name); no extension pixels, layouts or code over the
    paper. Extension-owned *screens* are allowed only off the paper (future — the tiered rule under
    §"Selection-toolbar contributions"). Row 24.
21. **Absent provider = placeholder, never a broken page.** Rows render as a dashed box, stay
    selectable / movable / deletable, and come back to life when the provider returns. Row 23.
22. **Every action is one undoable step**, including the strokes it consumes (`ObjectCreated` carries
    the removed strokes; `ObjectEdited` the before/after payload + bounds; `ObjectsDeleted` strokes and
    objects together).
23. **Capabilities reach a provider only as in-parameters** (`IHandwritingRecognizer`,
    `IMarkdownRenderer` proxies), null when absent; a provider says what it needs (`requires`) so the
    core can explain before binding. Rows 20 / 23.
24. **An outline is a description, not a parse** (arc 5). The core never derives structure from a
    payload; a provider *describes* each object's outline entry (`describeOutline` → label + level, or
    level 0 for none — the same-length, same-order reply) and the core sorts, nests, pages and draws
    under its own rules (`OutlineTree`, `ContentsDialog`). A provider that predates the method, is
    absent, or fails, contributes nothing and nothing else changes — and a method appended to a
    provider interface is *probed* at load, never assumed (§"Versioning rules"). Rows 25–27.

Followed by ObjectProvider (H3/H4): `OBJECT_PROVIDER` + `IObjectProvider` + `CreatedObject` /
`SelectionAction` / `EditSpec` in `:extension-api`; `ExtensionRegistry.objectProviders` +
`ObjectProviderClient`; the core owns every dialog and every pixel of chrome; the one recorded widening
of rule 5 is bare ink into `createFromInk` (row 19); audit rows 18–24 (H5). Extended in arc 5 (C0–C2)
by the appended `describeOutline` — the first exercised compatible AIDL change (`API_VERSION` still 1),
the load probe + `OutlineCaps`, and the core-drawn Contents; audit rows 25–27 (C2).

### Adding a screen-owning point (arc 6 pattern)

A point whose extension owns a **screen** — an off-paper Activity the core launches for a result
(the second tier of the UI rule; the Scratch Pad is the first exercise) — follows rules 1–5, the store
rules where it keeps data, **plus**:

25. **A screen is the extension's, its data is the host's, the transfer is the point's.** An
    extension-owned screen holds nothing but what the host lent it for that showing (the store
    binder, the inbound ink) and hands back only through the point's methods — never through the
    Intent, never through a file, never through a shared process. (Rows 28–31.)
26. **A held bind is still bind-per-operation** — the operation is the showing. It is opened before
    the screen and closed (unbind + revoke) in one `finally` after it, on every path including the
    caller's death (`ExtensionBinder.hold` → `HeldBinding`; the client's `finish` from the result,
    the cancel, a failed open and the caller's `onDestroy`). (S3 re-walk of rows 1/6/7.)
27. **Two paper surfaces never share a process, a view, or the EPD pipeline at once** —
    `releaseForHandoff()` before the launch **and** before every `finish()` back to the caller,
    `resumeDrawing()` on return on both sides, and the extension registers its own g-paper engines
    in its own `Application` (g-paper `docs/api.md` §Lifecycle; the handoff is symmetric — a miss
    on either side tears the other's raw session down, seen on NA5C in S2). The engines' ownership
    guards are **process-local**, so the departing side's `releaseForHandoff()` must be its *full*
    teardown and drop the token — a late focus-loss / `release()` teardown from the other process
    otherwise lands after the caller's reclaim (Ratta: the panel left full-UI-auto → slow-waveform
    drags, seen on SNN in S3 → g-paper 0.1.2). (Row 31.)

Followed by ScratchPad (arc 6): `SCRATCH_PAD` + `IScratchPad` + `PaperStroke` / `InkBundle` in
`:extension-api`; `ExtensionRegistry.scratchPad` + `ScratchPadClient` over `ExtensionBinder.hold`;
the recipe below; audit rows 28–32 (S3).

### Extension-owned screens (tier 2) — the recipe

The five steps a later screen-owning extension follows (frozen from §"ScratchPad (contract)"):

1. **Discovery + trust as every point** (`ExtensionRegistry.<point>` — the first trusted service for
   the action; a second installed one is ignored, like the namer).
2. **Pre-open the store on IO, hold one bind for the screen's life** (`ExtensionBinder.hold` →
   `HeldBinding`), call the point's opening method (`begin(store)`, ≤ 2 s), and only then launch the
   screen with an **`ActivityResultLauncher<Intent>`** — after `paper.releaseForHandoff()` on any
   paper-hosting caller. Any in-bound payload (ink) goes over the held bind **between** the opening
   call and the launch, capped and chunked before the bind.
3. **The screen is an exported Activity with a custom action and no launcher filter**; it verifies its
   caller **first thing in `onCreate`** (`HostCallerCheck.enforceActivity` — else finished before
   anything is inflated), reads only the recorded `EXTRA_*` booleans, returns only the recorded
   `RESULT_*` codes; data never rides the Intent. It registers the g-paper engines in its own
   `Application`, and every exit back to the caller runs `releaseForHandoff()` before `finish()`.
4. **On the result (any code), on the launcher's cancel, and in the caller's `onDestroy` while the
   screen is up:** the out-bound payload is drained on the still-held bind (validated, capped), then
   `end()` → unbind → revoke the store binder, in one `finally` (the client's `finish`). The extension
   treats a dead binder / a `SecurityException` from the store as "unavailable" → an honest dialog and
   `finish()`.
5. **The core decides what the user sees on every failure** (rule 3): the extension's screen shows
   dialogs **only about its own state** (page full, store unavailable); the core owns the dialogs
   around the transfers, and a host-side paste of what came back is **one** undoable step.

Shared UI between the core and such an extension lives in `:paper-screen` (resources + screen
helpers, g-paper `api`, no contract dependency — §"The extension model"); a fix to shared screen
logic goes there, never in a consumer.

### The capability pattern (recorded in arc 3, **built in arc 4 / H3** with the first consumer)

A **capability point** is an extension point whose implementation the core lends to *other*
extensions. The recipe, fixed by arc 3 and built as written in H3 (`RecognizerProxyBinder`,
`MarkdownProxyBinder`, `ProxyGate` — see §"MarkdownRenderer / ObjectProvider — host behaviour"):

- The capability's AIDL is the interface both the provider **and** the core's proxy implement.
- The core is the only binder of the provider (`RecognizerClient`). A consumer point that needs the
  capability takes it as an **in-parameter** (`IHandwritingRecognizer recognizer`) on the calls that
  need it — exactly like `IExtensionStore`.
- The consumer client mints a **`RecognizerProxyBinder(client, extUid)`** per bind, uid-gated and
  revocable (`ExtensionStoreGate` shape), which re-applies the `MAX_INK_*` caps inward and forwards to
  `RecognizerClient` (own bind, own timeouts, own signature check) — a proxy call is therefore two
  hops and must fit inside the consumer call's timeout; the consumer's timeout is sized accordingly.
- No provider installed → the consumer receives `null` for the parameter (AIDL allows a null
  interface) and must cope; the core never fakes a recognizer.
- Shape in detail (from `PAPER_RECOGNITION_PLAN.md` §Deferred): mint in the consumer client's `call`,
  revoke in the unbind `finally`, gate on `getCallingUid() == extUid && !revoked`, re-apply
  `MAX_INK_*` inward, then forward through `RecognizerClient` with the same timeouts.

## Writing an extension

What a third party will do once `:extension-api` is published (today: the same steps, with the module
consumed in-project — see `:ext-templates` for the reference implementation).

1. **Depend on the contract:** `implementation("com.symmetricalpalmtree.notesprout:extension-api:<v>")`
   (in-project: `implementation(project(":extension-api"))`). Nothing else from Paper.
2. **Be an app with no launcher Activity.** `com.android.application`, your own `applicationId`, an
   `<application>` with a label (`NSE · <Name>` — that label is what a future Extensions UI shows) and
   the puzzle icon (see the naming + icon convention), **no Activity**, `allowBackup="false"`.
3. **Declare one exported `<service>` per extension point** with the point's action in its
   intent-filter and the API version as meta-data:
   ```xml
   <service android:name=".TemplateProviderService" android:exported="true">
       <intent-filter>
           <action android:name="com.symmetricalpalmtree.notesprout.extension.TEMPLATE_PROVIDER" />
       </intent-filter>
       <meta-data android:name="com.symmetricalpalmtree.notesprout.extension.API_VERSION" android:value="1" />
   </service>
   ```
4. **Implement the stub.** `onBind` returns an `ITemplateProvider.Stub`. Methods run on Binder threads
   — hold no mutable state, or synchronise. `listTemplates()` returns stable ASCII ids (unique within
   your package) with display names; `render(id, w, h, dpi)` returns a complete lossless WEBP of exactly
   `w × h` in a `SharedMemory` (`create` → map RW → write → unmap → `setProtect(PROT_READ)`), or null
   for an unknown id. The host rejects a payload whose decoded size differs from the request. Close
   your handle after the reply is written (`onTransact` `finally`, as `:ext-templates` does). Keep a
   render under 15 s on an e-ink CPU. Note `WEBP_LOSSLESS` is API 30+ — on API 29 use `WEBP` at
   quality 100 (lossless).
5. **Check the caller in every method** — call `HostCallerCheck.enforce(context, hostPackage)` from
   `:extension-api` first thing in every stub method (host package = `com.symmetricalpalmtree.notesprout`,
   or `.dev` for the debug host): the caller uid must map to the host **and** share your signature,
   else it throws `SecurityException`. In API v1 the host only binds same-signature extensions, so a third-party
   extension is not yet reachable — the trust rule lifts with the Extensions-UI arc's consent step.
6. **Never** reorder or remove AIDL methods or parcel fields; follow the versioning rules above.
7. **Using the store** (points whose AIDL passes an `IExtensionStore`, e.g. `INotebookNamer`):
   - The host hands you a **fresh binder per bind**, scoped to your uid and revoked the moment the
     host unbinds — never cache it in a field, never use it from another thread after the call
     returns; do your reads/writes inside the method that received it.
   - It is a key/value store: `get(key): ByteArray?`, `put(key, value)`, `delete(key)` (no-op if
     absent), `keys(prefix): List<String>` (`""` = all, ascending). Serialise however you like — the
     Naming extension stores UTF-8 text; there is no schema, no SQL, no namespace.
   - **Caps** (`ExtensionContract.STORE_*`): key `1..512` chars, value `≤ 4 MiB` (`STORE_MAX_VALUE_BYTES`;
     `put` / `get` carry up to `STORE_MAX_INLINE_BYTES` 512 KiB inline — above that use `putLarge` /
     `getLarge` with a `LargeValue` region through `SharedBytes`, and a `get` of a large stored value
     throws `IllegalStateException(STORE_VALUE_LARGE)`), at most `50 000` keys per extension. Over a
     cap → `IllegalArgumentException` / `IllegalStateException` from the host.
   - **Treat any exception as "store unavailable"** — `SecurityException` (revoked / wrong uid),
     `IllegalArgumentException`, `IllegalStateException`, `RemoteException`, and anything else. Catch it
     and rethrow one of the exceptions Binder carries intact (`IllegalStateException` is what
     `NotebookNamerService.storeCall` uses) so the host sees a clean failure rather than a dead
     process; never let it escape uncaught.
   - **Key naming:** prefix keys by kind (`folder:<uuid>`, `pref:<name>`, …) so `keys("folder:")`
     stays cheap and a later "remove data for X" is a prefix walk. Keys are opaque to the host.
   - Your data is encrypted at rest under the user's global key, lives in the **host's** files dir,
     and **survives your uninstall** — the user (via a future Extensions UI), not you, decides when it
     is removed. Debug (`.dev`) and release builds of your extension get separate stores.
8. **Implementing a recognizer** (`HANDWRITING_RECOGNIZER` — `IHandwritingRecognizer`, see
   `:ext-mlkit` for the reference implementation):
   - **Bare geometry in, text out.** You receive `List<InkStroke>` (parallel x/y `FloatArray`s in the
     caller's px space — no ids, no time, no pressure, no colour), a writing-area or page size, and for
     `recognizeInk` up to 20 chars of pre-context. `recognizeInk` recognizes one writing area (no
     layout analysis); `recognizePage` receives a whole page and **you** segment it into lines and
     paragraphs and chain context yourself (the core has no HWR/layout knowledge). Return plain text
     (`""` if nothing) — lines joined by `\n`, paragraphs by a blank line.
   - **`status()` / `prepare()` protocol.** `status()` must be fast and never wait on your engine
     (`READY` / `NEEDS_DOWNLOAD` / `DOWNLOADING` — everything in flight — / `UNAVAILABLE`); `prepare()`
     starts acquiring what you need (a model download) and returns at once — idempotent. The recognize
     calls may arrive before you are READY: wait for an acquisition **already in flight** inside the
     caller's timeout (10 s ink · 30 s page — leave room for the recognition itself, and stop at your
     own deadline just under it) — never start a download from a recognize call — and throw
     `IllegalStateException(ExtensionContract.RECOGNIZER_NOT_READY)` (that exact message) if you cannot
     become ready in time; any other `IllegalStateException` for an engine failure or timeout; over the
     `MAX_INK_*` caps → `IllegalArgumentException`. Only those two plus `SecurityException` cross
     Binder intact. `prepare()` is the **only** call that may start a download — the host asks the user
     first. Handle single-point strokes (pen taps are visible dots).
   - **Engine assets live in your own sandbox** — the one exception to "extension data goes to the
     host store": a downloaded model or bundled weights are not user data. Anything the *user* owns
     still goes to the host store (this point passes none).
   - **Never log recognized text** — counts and durations only, on your side as on the core's. Every
     stub method still begins with `HostCallerCheck.enforce`.
   - You are a **capability provider**: the core binds you and lends you to consumer extensions
     (object providers, since arc 4) through its own proxy — you never see them, and they never bind you.
9. **Implementing a Markdown renderer** (`MARKDOWN_RENDERER` — `IMarkdownRenderer`, see
   `:ext-markdown` for the reference implementation):
   - **Text + layout numbers in, one image out.** `render(markdown, maxWidthPx, dpi, maxLines,
     paddingPx)` receives markdown source (≤ `MAX_MARKDOWN_CHARS`) and layout numbers only — never an
     id, name or path — and returns a `RenderedImage` (lossless WEBP with alpha in a `SharedMemory`
     region, `PROT_READ`, `byteCount` + declared `widthPx × heightPx` that **must** match the encoded
     header — the host rejects the image otherwise) or **null** for a source that renders to nothing
     (blank). Re-check the arguments yourself (`IllegalArgumentException` on a violation); keep every
     edge ≤ `MAX_IMAGE_EDGE_PX` (ellipsize / clamp), padding is part of the image, `maxLines == 0` means
     unbounded. Park the region per Binder thread and **close it in `onTransact`'s `finally`** once
     the reply (a dup of the descriptor) is marshalled — the Templates handshake.
   - **Typography is yours.** The core knows no font, size or multiplier; a heading's look is entirely
     the renderer's (Notesprout's: 24 sp bold, H1 2.0 … H6 1.0, sp → px through the `dpi` you are
     given). Draw only what markdown says — no colour beyond black-on-transparent for an e-ink host.
   - **Stateless, ≤ 5 s per call** on the host's clock (leave room for encoding on an e-ink CPU),
     `HostCallerCheck.enforce` first in every method, log sizes + durations only — **never the text**.
     You are a **capability provider**: object providers reach you only through the core's proxy.
10. **Implementing an object provider** (`OBJECT_PROVIDER` — `IObjectProvider`, see `:ext-heading` for
    the reference implementation):
    - **You own object *types*; the core owns object *rows*.** Declare your typeIds in
      `describeTypes()` (`[A-Za-z0-9_.-]{1,32}`, ≤ `MAX_TYPES`); the core stores every object as a row
      with `<yourPackage>:<typeId>` + an **opaque payload** you choose (≤ `MAX_OBJECT_TEXT_CHARS`) +
      geometry. Keep nothing about a specific object anywhere — every method receives the payload it
      needs and returns a new one; the core stores it. Never parse anything but your own payload.
    - **Describe your UI, don't draw it.** `describeActions()` returns `SelectionAction`s (id, ≤ 6-char
      label, an icon **name** from the core catalog — `IconNames` — or null for a text button, up to
      one level of sub-actions, `appliesTo` = `INK` / `OBJECT` bits, `requires` = `RECOGNIZER` /
      `MARKDOWN` bits so the host can explain a missing capability *before* binding you);
      `activeActionIds(typeId, payload)` names the leaves to draw as selected; `describeEdit` returns an
      `EditSpec` (title, prefill without your markup, hint, maxChars, multiLine) or null for "not
      editable". The core draws every button, sub-toolbar and dialog itself; you never see the screen.
    - **Capabilities arrive as in-parameters, or null.** `createFromInk` gets an `IHandwritingRecognizer`
      and `render` an `IMarkdownRenderer` — the core's proxies (uid-bound to you, valid for that one
      call, same caps as the real thing); throw `IllegalStateException(RECOGNIZER_REQUIRED /
      MARKDOWN_REQUIRED)` when you need one and it is null. Never bind a recognizer or renderer
      yourself; a proxy call is a second hop, so stay well inside 15 s (`createFromInk`) / 10 s
      (`render`); the pure methods must answer in ≤ 2 s.
    - **Return null for "nothing" / "no change".** `createFromInk` null → the core leaves the ink
      alone (unreadable ink is not an error); `applyAction` / `applyEdit` null → the payload is
      untouched; `render` null → the object draws as a placeholder. Every success is one undoable step
      on the core's side — you never see undo.
    - **Ink in is bare** (x/y in page px, writing order, the selection bounds' size as the writing
      area); a `render` may return the Markdown proxy's `RenderedImage` **as-is** (the region is the
      reply) — park your handle per Binder thread and close it in `onTransact`'s `finally` like a
      renderer does. `HostCallerCheck.enforce` first in every method; only `SecurityException` /
      `IllegalArgumentException` / `IllegalStateException` cross Binder; log counts + durations —
      **never a payload or recognized text**.
    - **Contribute to the Contents by describing, not drawing** (arc 5). Implement `describeOutline
      (typeId, payloads)` — appended after `render`, so an extension built against the arc-4 AIDL simply
      never receives it and the host treats it as "no outline" — returning **one `OutlineEntry` per
      payload, same order, same length**: `level 1..MAX_OUTLINE_LEVEL` + a label ≤ `MAX_OUTLINE_LABEL_CHARS`
      for an outline item, or `OutlineEntry.NONE` (`level 0`, label ignored) for "not listed" — a reply
      of any other length is discarded whole. Pure and ≤ 2 s per call; the host chunks at
      `MAX_OUTLINE_BATCH` / `MAX_OUTLINE_BATCH_CHARS` and re-checks nothing you can't (re-check the two
      caps yourself → `IllegalArgumentException`) — but **answer every type you declare** (`NONE` for a
      type you don't outline; refusing one of your own types with an exception fails the whole
      Contents for your objects). **The host probes you at load** with one blank
      payload and expects a one-entry reply — answer it like any other (the Heading returns `NONE`).
      You never see page numbers, positions or ids, and you never build the list: the core sorts, nests
      (H1–H6, orphans attached), highlights and draws the Contents from your labels and levels alone.
      Log the count + duration — never a label.
11. **Owning a screen** (`SCRATCH_PAD` — `IScratchPad`, see `:ext-scratchpad` for the reference
    implementation; the tier-2 recipe under §"Extension-owned screens (tier 2)"):
    - **Your Activity is exported with a custom action and no launcher filter**, and the first line of
      `onCreate` after `super.onCreate` is `if (!HostCallerCheck.enforceActivity(this, hostPackage)) return` — the host launches
      you with an `ActivityResultLauncher` (that is what gives you a `callingPackage`); a plain `am start`
      must be refused. Read only the recorded `EXTRA_*`, return only the recorded `RESULT_*`; **no data
      rides the Intent** in either direction.
    - **The service call that opens the showing brings the store; keep it only for the showing.**
      `begin(store)` runs before your screen is launched — park the binder for the screen's life and
      drop it on `end()`; after the host unbinds it is revoked, so treat every `SecurityException` /
      dead binder as "store unavailable": an honest dialog and `finish()`. Values above
      `STORE_MAX_INLINE_BYTES` go through `putLarge` / `getLarge` (`SharedBytes`); close your region
      handle in `finally` on both paths. A value over `STORE_MAX_VALUE_BYTES` must be refused, never split.
    - **Ink arrives and leaves as `InkBundle`s on the service, never on the Activity** — chunked
      (`TRANSFER_CHUNK_*`), capped (`MAX_TRANSFER_*` — re-check the running totals yourself and throw
      `IllegalArgumentException`), `requireValid` at unmarshal; mint your own stroke ids, trust nothing
      but geometry. Place inbound ink **on the Binder thread** before the screen opens; park outbound ink
      for `takeOutgoing(i)` and answer an empty bundle past the end.
    - **You host a g-paper surface in another process than the core's.** Register the engines in your
      own `Application`, call `paper.releaseForHandoff()` **before every `finish()` back to the host**
      and `resumeDrawing()` in `onResume` — the EPD pen pipeline is process-global and the handoff is
      symmetric (g-paper `docs/api.md` §Lifecycle).
    - **Build your chrome from `:paper-screen`** (the e-ink resources, `PaperToolbar` / `PaperChrome` /
      `PageGestures` / `UndoRedoStack` / `ToolbarAnchor`, `Dialogs`, the Tabler icons) so the screen is
      the notebook's shape; fix shared screen logic there, never in your copy. `HostCallerCheck.enforce`
      first in every service method; log counts + durations — never ink.

---

## Build & install

```sh
cd ~/git/Notesprout/apps/notesprout_paper
./gradlew assembleDebug                       # all modules
./gradlew testDebugUnitTest                   # all modules
adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <serial> install -r ext-templates/build/outputs/apk/debug/ext-templates-debug.apk
adb -s <serial> install -r ext-naming/build/outputs/apk/debug/ext-naming-debug.apk
adb -s <serial> install -r ext-mlkit/build/outputs/apk/debug/ext-mlkit-debug.apk        # ML Kit model downloads on first prepare() (Wi-Fi once per device)
adb -s <serial> install -r ext-markdown/build/outputs/apk/debug/ext-markdown-debug.apk  # the Markdown renderer (arc 4)
adb -s <serial> install -r ext-heading/build/outputs/apk/debug/ext-heading-debug.apk    # the Heading object provider (arc 4 / H3)
adb -s <serial> install -r ext-scratchpad/build/outputs/apk/debug/ext-scratchpad-debug.apk  # the Scratch Pad — owns a screen; ~25 MB (g-paper + the Onyx SDK) (arc 6)
adb -s <serial> shell pm enable com.symmetricalpalmtree.notesprout.ext.templates.dev          # BOOX sideload trap
adb -s <serial> shell pm enable com.symmetricalpalmtree.notesprout.ext.naming.dev
adb -s <serial> shell pm enable com.symmetricalpalmtree.notesprout.ext.mlkit.dev
adb -s <serial> shell pm enable com.symmetricalpalmtree.notesprout.ext.markdown.dev
adb -s <serial> shell pm enable com.symmetricalpalmtree.notesprout.ext.heading.dev
adb -s <serial> shell pm enable com.symmetricalpalmtree.notesprout.ext.scratchpad.dev
adb -s <serial> shell pm disable-user --user 0 com.symmetricalpalmtree.notesprout.ext.templates.dev  # simulate "not installed"
adb -s <serial> uninstall com.symmetricalpalmtree.notesprout.ext.templates.dev
```

All seven APKs are signed by the same debug keystore (`~/.android/debug.keystore`) — that is what satisfies
the same-signature trust rule in dev. An extension built on another machine will **not** be trusted by
this Mac's core build (different debug key) — expected, not a bug.
