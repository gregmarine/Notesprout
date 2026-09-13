# Objects — sticky notes, text objects, six shapes (arc 28 "Objects")

Arc 28 adds three new kinds of content to an SN page: **sticky notes** (an icon that opens its own
paper surface), **on-page Markdown text objects**, and **six hand-placed shapes** (rectangle,
ellipse, triangle, line, arrow, star). Together with strokes, headings and links (core since arc 3
and arc 6) they are the whole object catalog. The user chose three of the four kinds og Notesprout
(`apps/notesprout_android`, reading reference only — no code copied) has; **line objects and the
dwell-triggered shape recognizer are OUT** by explicit decision, and neither is planned.

All three kinds are **core**, following the shape arc 3 set for headings: an additive row type on
the universal `notebook` table (no `SOIL_VERSION` bump, no migration, no new column — rows pack
into the 18 columns every row already has), a pure row mapper, a store on the shared `SoilWriter`,
a g-paper `ContentRenderer`, undo actions, clipboard and page-copy arms, and export through
`PagePreview`. Nothing in this arc is an extension. There is **no ninth extension point** and **no
`API_VERSION` bump** — the one seam crossing is a compatible trailer on the PDF page bundle
(`PageBundle` v2, backward-readable) for the sticky-notes-as-PDF-endnotes treatment. App version
stays `0.1.0-ratta` throughout.

Two things push this arc past three copies of the heading recipe:

- **Sticky notes get an editor with its own paper surface** — `StickyEditorActivity`, a core
  `:app` Activity, the second second-paper-surface in one process (after the calendar's event
  note, arc 24 / Z3), sharing the notebook's open `.soil` connection rather than opening its own.
- **Shapes need resize and rotate**, and g-paper's selection is move-only, so a **transform mode**
  landed in the engine itself (`~/git/g-paper` 0.1.27) — the arc's one engine phase and its
  headline risk.

## Phases

| Phase | Date | What landed |
|---|---|---|
| H1 | 2026-09-06 | The substrate: `TYPE_TEXT`/`TYPE_SHAPE`/`TYPE_STICKY` rows, mappers, stores, the three `ContentRenderer`s in D8 draw order, undo/clipboard/kind-list arms, the Insert bar shell (debug-only). Nothing user-creatable yet. |
| H2 | 2026-09-06 | Text objects end to end: `TextEditDialog`, `TextFlow` (insert/convert/edit), `SelectionMode.TEXT`, the lasso bar's Text, `armLassoForLanding()` shared with transfer pastes. |
| H3 | 2026-09-06 | g-paper transform mode landed in the engine (0.1.27): handles, rotate knob, aspect lock, 5° snap. SN re-pinned, no host behaviour change yet. |
| H4 | 2026-09-06 | Shapes on the page: `ShapeFlow`, `ShapeTransformBar`, `SelectionMode.SHAPE` + the lasso bar's Transform, all six shapes on the Insert bar, finger gates yield during a transform. |
| H5 | 2026-09-06 | Sticky notes: `StickyEditorActivity` on its own g-paper surface, `StickyEditorTransfer`, `StickyFlow`, finger-tap reopen, `SelectionMode.STICKY`. |
| H6 | 2026-09-06 | PDF endnotes: `PageBundle` v2 trailer, `ExporterInfo.bundleVersion` tail, `:ext-pdf` link annotations, host `Endnotes`/`ExportRender` bake, the Export screen's one-line notice. |
| H7 | 2026-09-06 | Docs and freeze (this document). No code, no code review. |

The arc-range `/code-review high` planned for H6 was **waived by the user** at that phase's start
— H6 shipped without it, and H7 carries no code review either.

## Decisions (binding, wizard 2026-09-06)

| # | Decision | As built |
|---|---|---|
| 1 | Sticky editor's home | A core Activity in `:app` — `StickyEditorActivity`, its own g-paper surface, same process as the notebook. No ninth point, no seam crossing, one `SoilWriter`. |
| 2 | Sticky create / open flow | The Insert bar's Sticky inserts a 72 dp square icon at page centre, records the undo, and opens the editor immediately. On close from an initial create the icon lands **selected under the lasso**. Reopen later with a **finger tap** (stylus taps stay ink). |
| 3 | Sticky editor screen | Full-screen, notebook's tools plus paste. **As walked**: top bar `[←] [pen] [eraser] [lasso]`, a centred "Sticky Note" title — the **✓ button was dropped** on the H5 walk (Back already saves-and-closes; there is no cancel, every stroke is already a row). Lasso bar Snap · Copy · Cut · Delete. Pastes from / copies to the global clipboard. **Restructured arc 33 / F2**: the paper went full-bleed under the floating top bar, matching the other three paper screens (§ Sticky notes, below). |
| 4 | Sticky export | PDF endnotes, og's treatment: an endnote page per note after the last page, caption `Note N — from page P`, two-way PDF link annotations. Text export excludes sticky content; `.soil` export carries the rows verbatim. |
| 5 | Text object creation | Both paths: lasso bar **Text** recognizes ink via `HeadingConvert.run(multiLine = true)` (failure creates nothing), and the Insert bar's **Text** inserts empty + opens `TextEditDialog` at once (Cancel/blank Save removes it). |
| 6 | Text edit dialog | A plain markdown `AppCompatEditText`, Save / Cancel, blank Save = delete. Renders through `:markdown` at 24 sp black, multi-line, wrapped to the page-capped width. A stylus tap on a lone selected text opens it. |
| 7 | Shape types | Six: `RECTANGLE`, `ELLIPSE`, `TRIANGLE`, `ARROW`, `LINE`, `STAR`. Square/circle are the aspect-locked rectangle/ellipse, not separate types. og's diamond/trapezoid/pentagon/hexagon/arch are not built. |
| 8 | Shape sizing / editing | A transform mode landed in g-paper (**0.1.27**, not the plan's default guess of 0.1.25 — two Paintsprout phases took 0.1.25/0.1.26 the same day): 8 handles, a rotate knob, aspect lock, 5° snap, 24 dp minimum size. The rotate knob is offered for **every** type (not just LINE/ARROW). |
| 9 | Parity depth | Full in-notebook parity for all three kinds (select/move/delete, erase, undo/redo, copy/cut/paste within and across notebooks, cross-notebook page copy). No extension transfers; `:ext-ink`'s `InkWire` untouched. Send to Scratch Pad / Calendar **hide** (GONE) whenever the selection holds a new kind. |
| 10 | Toolbar | One `Insert` button (`ic_plus`, after the lasso) opening `InsertBar`: Sticky · Text · Rectangle · Ellipse · Triangle · Line · Arrow · Star. All eight fit **one row** on the Nomad — the two-row wrap contingency was never needed. |
| 11 | Phases / review | Seven phases H1–H7 on this standalone plan. `/code-review high` was planned for H6 but **waived by the user** at that phase's start; H7 is docs-only with no code review either. |
| 12 | App version | Stays `0.1.0-ratta` throughout — reconfirmed at every phase start. |

Star point count came up as an H4 phase-start question and was **fixed at 5** (`ShapeFlags.DEFAULT_POINTS`) rather than exposed as a bar control — a control is a backlog item, not a rejected idea.

## Collaborators

| File | Owns |
|---|---|
| `notebook/TextRows.kt` | `PageText` + `TextRows.toRow`/`toText` — pure mapper, JVM-tested |
| `notebook/TextStore.kt` | Text row writes on the shared `SoilWriter` |
| `notebook/TextRenderer.kt` | `TextRenderer.measure` (the one sizing function) + `drawText`; renders through `:markdown`'s `MarkdownRenderer`/`MarkdownDraw` at `BASE_SP` = 24 sp |
| `notebook/TextFlow.kt` | `TextFlow` + `TextFlow.Host` — insert/convert/edit out of the activity; nothing exists until Save on an insert |
| `notebook/TextEditDialog.kt` | The plain markdown edit dialog, `HeadingEditDialog`'s shape |
| `notebook/TextLines.kt` | Pure: `normalize` (recognized ink) vs `typed` (dialog text) — two deliberately different whitespace rules |
| `notebook/TextPlacement.kt` | Pure: `centred` box placement for an inserted text |
| `notebook/ShapeRows.kt` | `PageShape`, `ShapeType`, `ShapeFlags` (pack/unpack of aspect/pointCount/rotation) — pure, JVM-tested |
| `notebook/ShapeStore.kt` | Shape row writes on the shared `SoilWriter` |
| `notebook/ShapeGeometry.kt` | Pure: `outline`, `tightBounds`, `aabb`, the thin `pathFor` — one object for geometry and hit-bounds alike |
| `notebook/ShapeRenderer.kt` | `drawShape`/`drawObject` — stroke-only, `BELOW_STROKES` |
| `notebook/ShapeFlow.kt` | `ShapeFlow` + `ShapeFlow.Host` — insert and the whole transform lifecycle |
| `notebook/ShapeDefaults.kt` | Pure: og's insert sizes/aspect defaults, `MIN_SIZE_DP` |
| `notebook/ShapeBox.kt` | Pure: `PageShape` ↔ g-paper `OrientedBox` conversion |
| `notebook/ShapeTransformBar.kt` | The floating aspect-lock + Done bar, placed clear of the overlay's reach |
| `notebook/ShapeTransformLabels.kt` | Pure lookup table: what the aspect-lock button currently says, per type |
| `notebook/StickyRows.kt` | `PageSticky`, `StickyFlags` (contentW/contentH packing) — pure, JVM-tested |
| `notebook/StickyStore.kt` | Sticky row + child-stroke writes; `remove`/`restore` carry children with the note |
| `notebook/StickyRenderer.kt` | Draws `ic_sticker_2` scaled into the icon box; content never drawn |
| `notebook/StickyFlow.kt` | `StickyFlow` + `StickyFlow.Host` — insert-then-open, finger-tap reopen, the one `StickyContentEdited` per showing |
| `notebook/StickyEditorActivity.kt` | The host editor Activity — its own `PaperView`, top bar, lasso bar, undo/redo, clipboard |
| `notebook/StickyEditorTransfer.kt` | Process-local singleton: stage/take-once/leave/clear, the `Sink` bound to the notebook's `SoilWriter` |
| `notebook/StickyInk.kt` | Pure in-editor undo actions: `Drew`/`Erased(indexed)`/`Moved`/`Pasted` |
| `notebook/StickyClip.kt` | Pure: copy = stroke rows parented to the sticky id; paste = stroke rows only, `leftOut` measured against what was dropped |
| `notebook/StickyDefaults.kt` | Pure: 72 dp icon size; `contentSize(windowW, windowH)` computed by the notebook — the whole window since arc 33 / F2, the `topBarPx` parameter removed (not zeroed) now that the editor's paper is full-bleed |
| `notebook/StickyPageRects.kt` | Arc 33 / F2, pure: `offPage(pageW, pageH, viewW, viewH): List<Band>` — the band(s) an older, shorter note leaves over in the full-bleed view (below full-width, right page-height, never overlapping); `Band.toRect()` is the one Android line |
| `notebook/InsertBar.kt` | `InsertBar.Kind` (9 values since arc 38 / R3 — `BIBLE` appended), `shapeType(kind)` routing (`BIBLE` maps to no `ShapeType`, `STICKY`'s and `TEXT`'s own answer), the floating sub-bar (`AnchoredBar` recipe), `offer(kind, offered)` — the one kind that still comes and goes after H5, gated on a Bible reader that understands references |
| `notebook/BibleRefFlow.kt` | Arc 38 / R3 — convert / insert / edit for a Bible reference: the recognizer, the reference dialog, the one resolve call, the wrap, the two undo actions. Kept out of `NotebookActivity` the way `TextFlow`/`LinkPickFlow` are |
| `notebook/BibleRefDialog.kt` | Arc 38 / R3 — the one-field reference dialog (`HeadingEditDialog`'s shape); a blank Save is a Cancel here, the one deliberate difference from every other object dialog |
| `notebook/SelectionModes.kt` | `SelectionModes.classify` — the pure `when` deciding `SelectionMode` from a selection's contents |
| `notebook/PageObjects.kt` | The three renderers + working copies (`texts`/`shapes`/`stickies`), a view-model beside the activity |
| `notebook/PagePreview.kt` | `PagePreview.drawContent` — the one page-layering recipe (PDF bake, link-picker preview), D8 order |
| `notebook/PageReads.kt` | `PageContent` grown with `texts`/`shapes`/`stickies` |
| `notebook/ObjectClip.kt` | `ObjectClip.plan`/`capture`/`payloadBounds` grown three arms; sticky children captured with the note |
| `notebook/NotebookUndo.kt` | Six new `Action` kinds, four widened with id lists; both replay `when`s exhaustive |
| `notebook/LinkComposite.kt` | Wraps texts/shapes/stickies the same way it already wrapped headings/strokes |
| `notebook/LinkStore.kt`, `notebook/LinkRows.kt`, `notebook/PageLink.kt` | Grown so a link's children lists include `texts`/`shapes`/`stickies` |
| `notebook/NotebookSession.kt` | `ORDERED_TYPES` = 6 entries; `captureObjects` reads sticky children |
| `notebook/TagSelection.kt` | Refuses `TEXT`/`SHAPE`/`STICKY` (ink-or-lone-heading rule unchanged) |
| `notebook/HeadingConvert.kt` | `run(multiLine = true)` — shared verbatim by the lasso bar's Text conversion |
| `notebook/AnchoredBar.kt` | The shared floating sub-toolbar geometry `InsertBar` and `ShapeTransformBar` both use |
| `data/soil/SoilSchema.kt` | `TYPE_TEXT`/`TYPE_SHAPE`/`TYPE_STICKY` literals (`text`/`shape`/`sticky_note`), pinned by `FamilyConstantsTest` |
| `data/soil/SoilDao.kt` | `liveContentIds`/`liveDescendantIds` grown the three kinds; `stickyIdsWithContent()` (one JOIN, H6) |
| `data/soil/DocumentDao.kt` | Three staleness whitelists gain the new kinds (a note's *inner* strokes deliberately excluded) |
| `export/Endnotes.kt` | Pure: `plan(sources, pageCount)` — numbering, note-page index, size fallback, two links per note |
| `export/ExportRender.kt` | Plans + bakes the endnote pages after the notebook's own pages, passes `bundleVersion` |
| `export/ExportDocumentRules.kt` | `endnotesUnavailable(...)` — the pure rule behind the Export screen's one-line notice |
| `export/ExportActivity.kt` | Reads `hasStickyContent` alongside `hasDocument` on one `readOnce`, shows the notice |
| `extension-api/.../extension/PageBundle.kt` | `VERSION = 2`, `VERSION_1 = 1`, `MAX_LINKS`, `Writer`/`Reader` with the compatible trailer |
| `extension-api/.../extension/ExporterInfo.kt` | `bundleVersion: Int = 1` compatible tail (the `sourceKind` precedent) |
| `ext-pdf/.../pdf/PdfDescriptor.kt` | Declares `bundleVersion = PageBundle.VERSION` |
| `ext-pdf/.../pdf/PdfLinks.kt` | Pure: `annotations(links, pageHeights)` — bundle rect → PDF annotation rect, vertical flip |
| `ext-pdf/.../pdf/PdfAssembly.kt` | Reads the trailer, adds `PDAnnotationLink`+`PDActionGoTo`+`PDPageFitDestination` per link before `protect()` |
| `sn-screen/.../notebook/FloatingSelectionBar.kt` | `buttonAt` — lets a bar (the sticky editor's lasso bar) query a button's own state (the Snap latch) |
| `res/layout/activity_notebook.xml` | `btnInsert` after `btnLasso` |
| `res/layout/activity_sticky_editor.xml` | Root `FrameLayout` since arc 33 / F2: `paperContainer` full-bleed first child, `topBar` (`[←] [pen] [eraser] [lasso]` + centred title) `layout_gravity="top"` as a later sibling, `selectionBar` / `eraserBar` last — a later `match_parent` sibling sits on top in a `FrameLayout` |
| `res/drawable/ic_sticker_2.xml` | og's Tabler `sticker-2`, given a white silhouette fill as its first path so the template never bleeds through |

Every pure piece above (`TextRows`, `ShapeRows`/`ShapeFlags`/`ShapeGeometry`, `StickyRows`/
`StickyFlags`, `TextLines`, `TextPlacement`, `ShapeDefaults`, `ShapeBox`, `ShapeTransformLabels`,
`StickyInk`, `StickyClip`, `StickyDefaults`, `SelectionModes`, `ObjectClip`, `ExportDocumentRules`,
`Endnotes`, `PageBundle`, `PdfLinks`) is JVM-tested with no Android dependency (or one that
degrades gracefully under `returnDefaultValues`). The stores, `Endnotes` over `ExportRender`'s fake DAO and `StickyStore.setContent` are exercised
through `FakeSoilDao`; the flows, `StickyEditorActivity` and everything that needs a real
`PaperView`/`StaticLayout` are walked by hand on the Nomad (there are no instrumented tests).

## Data model

Three additive row types on the universal `notebook` table (`SoilObjectEntity`: `id parentId type
order createdAt updatedAt deletedAt text refId x y width height color strokeWidth style flags
blob`). No column added, no `SOIL_VERSION` bump, no migration — a Paper-family reader ignores an
unknown `type`. `"order"` is per parent **and** type (its own counter, `maxOrder(parent, type)`),
as it already is for headings and links. **Nothing blank ever exists**: a text row always has
non-blank `text`, a sticky always has its icon box, a shape always has its type — a failed
recognition, a cancelled first dialog, or a blank Save leaves no row.

### Text row (`TYPE_TEXT = "text"`)

| Column | Holds |
|---|---|
| `parentId` | the page id |
| `text` | raw Markdown source — always non-blank; `TextRows.toText` returns null for blank/missing text or non-finite geometry, never crashes |
| `x` / `y` | the box's top-left, **authored** (fixed through an edit — the box grows down/right) |
| `width` | measured natural width, capped at `availableWidth = pageWidth − x` — never page width unconditionally |
| `height` | the laid-out height |
| `"order"` | z-order among the page's text rows |
| `flags` / `style` / `blob` / `refId` / `color` / `strokeWidth` | null |

`TextRenderer.measure(text, availableWidthPx, density, scaledDensity)` is the **one** sizing
function, called at creation, at edit, and again by `PageObjects` on every page load
(`remeasureForDevice` — position is authored, size is derived, so a note authored on one device's
density measures correctly on another's). It floors the wrap column at `MIN_WIDTH_PX` = 48 px.
Renders through `MarkdownRenderer.render(blocks, widthPx, paint, density, gap)` at
`TextRenderer.BASE_SP` = 24 sp on a black `TextPaint`, via `MarkdownDraw`, multi-line, no
`maxLines`, transparent background.

### Shape row (`TYPE_SHAPE = "shape"`)

| Column | Holds |
|---|---|
| `parentId` | the page id |
| `style` | the type name: `RECTANGLE` `ELLIPSE` `TRIANGLE` `ARROW` `LINE` `STAR` — an unknown value drops the row, never crashes |
| `x` / `y` | the **centre**, page px — the one row kind whose `x`/`y` is not a top-left |
| `width` / `height` | un-rotated local extents, page px |
| `strokeWidth` | the outline width in **px** (SN strokes are px throughout; og's dp is not copied), fixed at creation to the pen's 3 px |
| `flags` | `ShapeFlags.pack(aspectLocked, pointCount, rotationTenths)`: bit 0 aspect lock · bits 8–15 point count (STAR only; 0 reads back as `ShapeFlags.DEFAULT_POINTS` = 5, range 5–12) · bits 16–31 rotation in **tenths of a degree**, 0–3599 clockwise |
| `"order"` | z-order among the page's shape rows |

`ShapeGeometry` (pure, absolute page coordinates, rotation applied last about the centre):
`outline(shape)` returns the type's polygon (rectangle 4 corners; ellipse the box's oval; triangle
apex at top-centre; star alternating outer/inner vertices from the top with `STAR_INNER_RATIO` =
0.5; line `(L,cy)→(R,cy)`; arrow that line plus two arms at `ARROW_ARM_DEG` = ±150° from the shaft,
`ARROW_ARM_FRACTION` = 0.3 of width capped at `ARROW_ARM_MAX_PX` = 48 px); `tightBounds(shape)` the
point-tight rotated bounds; `aabb(shape, density)` = `tightBounds` inflated by
`max(strokeWidth/2, 4 dp)` — what `hitTargets()` reports and the lasso box shows; `pathFor(shape)`
the thin `android.graphics.Path` over the same numbers. **Hit-testing a rotated shape uses its
AABB**, not the rotated outline — og's accepted caveat.

### Sticky row (`TYPE_STICKY = "sticky_note"`)

| Column | Holds |
|---|---|
| `parentId` | the page id |
| `x` / `y` / `width` / `height` | the **icon box** in page px (72 dp × density at creation, square) |
| `flags` | `StickyFlags.pack(contentW, contentH)` — bits 0–19 content width px, bits 20–39 content height px (both ≤ 1,048,575) |
| `"order"` | z-order among the page's sticky rows |
| children | `stroke` rows with `parentId = <sticky id>`, geometry in **local content px**, `(0,0)` at the content's top-left |

`contentW × contentH` is minted **once, at creation, by the notebook** — the creating device's
window (`StickyDefaults.contentSize`; the whole window since arc 33 / F2, when the editor's paper
went full-bleed under a floating bar) — and never rewritten. The editor calls `setPageSize(contentW, contentH)` on every open, so a note
authored on a Nomad opens registered correctly on a Manta (the notebook's own foreign-page rule). A
sticky's drag rewrites **one row** (unlike a link, whose page-absolute children all move); only
`stroke` rows may be a sticky's children.

## Rendering

Three g-paper `ContentRenderer`s — `TextRenderer`, `ShapeRenderer`, `StickyRenderer` — all
`BELOW_STROKES`, held with their working copies in `PageObjects` (a view-model beside
`NotebookActivity`, not a store: nothing there writes a row). **D8 draw order**, registration = z:

```
headings · text · shapes · links · stickies · strokes
```

mirrored exactly by `PagePreview.drawContent` (loose headings/texts/shapes, then per link its
wrapped headings/texts/shapes/strokes, then loose sticky icons, then loose strokes) and by
`LinkComposite.build`, which wraps texts/shapes/stickies the way it already wrapped headings and
strokes. `PageObjects` re-measures every text on load and writes nothing back (position authored,
size derived). `TextRenderer.measure` is the one sizing function everywhere text needs a box.
`ShapeGeometry`'s `outline`/`tightBounds`/`aabb` are read by the renderer, the PDF bake, the
hit-test and the lasso box alike, so a shape can never draw in one place and be hit in another.
`StickyRenderer` draws the Tabler `sticker-2` glyph (`ic_sticker_2`, now with a white silhouette
fill as its first path so a page template never bleeds through the icon) scaled into the icon box
— **sticky content never draws on the page**, not in the notebook, not in covers, not in
`PagePreview`, not in the link-picker preview; the icon is the page's whole knowledge of it. Cover
snapshots pick up text, shapes and sticky icons for free (`renderToBitmap` walks the committed
layer) — verified by eye, no cover code needed.

## The Insert bar

`btnInsert` sits on the notebook's top bar directly after `btnLasso` (`ic_plus`). A tap opens
`InsertBar`, a floating `AnchoredBar` under the button — the same sub-toolbar geometry the H1–H6
recipe already used for other floating bars — holding, left to right: **Sticky · Text · Rectangle
· Ellipse · Triangle · Line · Arrow · Star** (`InsertBar.Kind`, 8 values; `shapeType(kind)` is the
one routing table from a Kind to a `ShapeType`, tested). It dismisses on a pick, any other bar
button, or an outside touch; the paper's exclusion rects union its bounds while it is up. Every
button carries a long-press hint. Measured on the Nomad first: **all eight fit in one row**
(~940 of 1404 px at `toolbar_button_size`) — the two-row wrap contingency in the design was never
needed.

**A ninth kind, arc 38 / R3: Bible reference.** `InsertBar.Kind.BIBLE` is **appended**, never
inserted beside Text — the eight arc-28 kinds were measured against the Nomad's bar as a row, and a
new kind joins the row's end, not its middle. It is the one kind that still comes and goes after
H5 offered the last of the original eight (`InsertBar.offer`, `GONE` until offered): re-offered
from `BibleEntry`'s own discovery on every `onResume`, gated the same way the lasso bar's Bible
button is — a reader declaring `MIN_API_VERSION_FOR_BIBLE_REFERENCE` (12), not merely one being
installed. A tap calls `BibleRefFlow.insertAtCentre()`, which opens the reference dialog **empty**
— nothing exists until it resolves, the same "no placeholder row, no minted id" rule every other
Insert-bar kind follows on Cancel.

Insert is a **command**, not a tool: the armed tool is unchanged by an insert, and the inserted
object lands selected under the lasso — but a host-landed selection under a pen tool is a picture
the pen just inks through, neither draggable nor tappable. This was H2's one walk failure (an
inserted text landed selected while the PEN tool was still armed). The fix, `armLassoForLanding()`,
is now called **before** every `setSelection` that lands a fresh object — the transfer-paste's
arm-lasso / remember-prior-tool / restore-at-dismissal recipe, pulled into one shared helper the
paste and every insert/convert path call. `TextFlow`, `ShapeFlow` and `StickyFlow` all call it.
A bar that opens a dialog (the Insert bar, the text dialog) uses an **ungated `releaseRender`** —
gating it on pen-idle would make a dialog wait for a hovering pen to leave (H2's other walk fix).

## Text objects

**Insert path**: the Insert bar's Text calls `TextFlow.insertAtCentre()`, which places an empty
box at page centre and opens `TextEditDialog` at once — **nothing exists until Save**: no
placeholder row, no minted id. Cancel or a blank Save on that first showing leaves nothing; Save
lands the new row selected (`armLassoForLanding()` first).

**Convert path**: the lasso bar's **Text** button, directly after **H**, calls
`HeadingConvert.run(multiLine = true)` — the same recognizer/readiness/`InkPayload` machinery the
heading conversion uses, byte-identical for both callers apart from the multi-line flag and the
absence of a heading prefix. **Recognition failure creates nothing** (the heading rule; og's
unrecognized-ink fallback state is deliberately absent) — the source ink is left untouched.

`TextEditDialog` copies `HeadingEditDialog`'s shape: one multi-line `AppCompatEditText` holding raw
Markdown source, Save and a **Cancel button on every showing** (create and re-edit alike — one
dialog shape, decided at H2 phase start), blank Save = delete. The IME is asked for on the way in
and never hidden — Ratta's hardware-keyboard-types-only-while-the-IME-is-shown rule — raised from
`onWindowFocusChanged` behind a once-per-showing latch. `TextLines` has two rules on purpose: a
recognizer's line breaks are a guess (`normalize` — collapse, per-line trim, at most one interior
blank line) while a typed line break is the author's decision (`typed` — per-line `trimEnd`, only
outer blank lines trimmed). `TextPlacement.centred` is the pure box-placement helper both the
Insert path and (via clamping) the paste path use.

A stylus tap on a lone selected text object opens `TextEditDialog` (the same gesture that opens a
heading, tried after the heading lookup misses). `SelectionMode.TEXT` (`SelectionModes.classify`)
offers Snap/Copy/Cut/Delete plus a link-free Link; no H button, no Transform. `TagSelection`
refuses `TEXT` (a tag is ink-or-lone-heading only). Move/delete/erase/scribble-erase/undo/redo,
copy/cut/paste within and across notebooks, and link-wrapping all work like every other content
kind (D6).

## Shapes and the transform mode

Six types, `ShapeDefaults` insert sizes (og's numbers): closed shapes (rectangle/ellipse/star) 72
dp square and aspect-**locked**; triangle 72 dp square, **free**; line and arrow 50% of page
width × 1 px, free; `MIN_SIZE_DP` = 24. Every insert lands at page centre, selected, with the lasso
bar up (`armLassoForLanding()` first). `SelectionMode.SHAPE` adds the lasso bar's **Transform**
button (`ic_resize`, directly after Text) to the base row; `TagSelection` refuses `SHAPE`.

### The g-paper transform mode (0.1.27)

An engine-owned overlay, host-agnostic — g-paper knows nothing about shapes. `PaperView`:
`beginTransform(contentId, box: OrientedBox, aspectLocked, minSizePx)` / `endTransform()` /
`setTransformAspectLocked(locked)` / `transformingContentId` / `transformBox`. `PaperListener`:
`onTransformChanged(contentId, box)` fires live (throttled to the lasso cadence during a drag, once
more at the lift) and `onTransformEnded(contentId, before, after)` fires **exactly once per mode on
every exit**, the host's own `endTransform()` included. `OrientedBox(cx, cy, w, h, rotationDeg)` is
clockwise, `[0, 360)`. The overlay: a dashed 1 dp oriented box, 8 handles (corners + edge
midpoints, 10 dp visual / 22 dp touch), a rotate knob 36 dp above top-centre, hairlines drawn at
`round(density)` px on integer edges. Resize anchors the opposite handle and clamps to
`minSizePx`; rotation snaps within 5° of the four cardinals. `beginTransform` is a **no-op** under
anything but `Tool.LASSO`. Exits: the bar's Done, a contact outside the grab region, a tool change,
any data-in call (`loadPageRaster`/`setSelection` included), an erase contact. **Ratta needed no
engine change**: the mode rides the shared lasso entries (`selectionBoxContains` answers for the
grab region, so the law-3 hover suppress already covers a handle drag). Landed in g-paper commit
`921cd9b` (Phase 15 of its own `PLAN.md`); SN re-pinned `sn-screen/build.gradle.kts` from 0.1.23 to
**0.1.27** (Paintsprout had taken 0.1.24–0.1.26 the same week for its own raster-page and
pixel-eraser work, entirely opt-in and a no-op for SN).

### The host contract, as built

This is the ledger's exact wording — it binds every later shape phase and any future transform
consumer:

- **Arm the lasso and dismiss selection chrome by hand before `beginTransform`**
  (`armLassoForLanding()` then `dismissSelectionChrome()`) — the engine dismisses the selection
  **without** firing `onSelectionDismissed`, and the mode is a no-op under a pen tool.
- A **declined** `beginTransform` (the id was not adopted) rolls back and re-selects the shape,
  since no `onTransformEnded` will ever come for it.
- `onTransformChanged` updates the `ShapeRenderer` **working copy only** (`objects.put`) — never
  requests a frame; the engine repaints the live shape itself via `drawObject`.
- `onTransformEnded` fires exactly once on **every** exit, the bar's own Done included. It compares
  the before/after `PageShape`s (a lock-only flip with no drag still counts as an entry), persists
  through `ShapeStore`, and records `Action.ShapeTransformed(before, after)` — this is the **only**
  persistence, undo, and chrome-teardown point.
- **Done re-selects** the shape (`setSelection` with `ShapeGeometry.aabb`); every other exit calls
  `restoreToolAfterTransferPaste()` to bring back whichever tool was armed before the transform
  began.
- `endTransformIfRunning()` is called at **eight** sites: `close()`, `onStop`, `navigateTo` (before
  `drain()`, so a same-page refresh reads the written geometry), both extension handoffs'
  `beforeLaunch` (a silent `releaseForHandoff`, no callback), and each of the three other floating
  bars' `show()` — another bar taking the screen ends the mode.
- **Finger gates yield** while `paper.transformingContentId != null`, exactly as they already do
  while a selection is active (`PageGestures.standDown` widened to
  `selectionActive || paper.transformingContentId != null`) — an H3 Nomad finding: a finger tap
  meant to exit the mode was instead being consumed as a move.

`ShapeTransformBar` is a floating bar of its own (not the lasso bar): a word-labelled aspect-lock
latch (bordered when locked) plus a **Done** button, placed clear of the overlay's reach (grown by
the knob's 36 dp stem + 14 dp knob + 22 dp touch radius) and re-placed only when the live overlay
actually reaches it. `ShapeTransformLabels.res(type, locked)` is the pure lookup table for the
latch's wording: ellipse "Circle"/"Oval", rectangle "Square"/"Rect", everything else "1:1"/"Free" —
the label names the shape's **current** state, the way the top bar's armed tool reads, never what
tapping would produce. `ShapeBox` is the pure `PageShape` ↔ `OrientedBox` conversion, normalizing
rotation through `ShapeFlags.normalizeDeg`.

## Sticky notes

**Create flow**: the Insert bar's Sticky inserts a 72 dp icon at page centre, records the undo
(`Action.StickyInserted`), and **opens the editor at once** (`StickyFlow.insertAtCentre` →
`open(stickyId, initialCreate = true)`). On close from that initial create the icon lands selected
under the lasso (`armLassoForLanding()` + `selectAsSticky`) so it can be dragged into place.

**`StickyEditorActivity`** — as built, not as originally sketched: `exported="false"`, launched by
`NotebookActivity` through an `ActivityResultLauncher`. Top bar `[←] [pen] [eraser] [lasso]` with a
centred **"Sticky Note"** title (`activity_sticky_editor.xml`). **Since arc 33 / F2 the paper is
full-bleed** — root `FrameLayout`, `paperContainer` the first child, `topBar` a later
`layout_gravity="top"` sibling, `selectionBar` / `eraserBar` last (the later-sibling-sits-on-top
trap) — matching the notebook, the pad and the calendar: a shown bar's opaque `paperWhite` covers
the ink beneath it and the pen refuses there by exclusion, exactly as any other floating bar.
**Back saves-and-closes; there is no ✓** — the original design had one, but the H5 walk dropped it
("Back already does the one thing a ✓ would") and put the title in its place. Fixed tools: pen ·
eraser · lasso, 2/3-finger undo/redo over an in-memory `StickyInk` stack, lasso bar Snap · Copy ·
Cut · Delete (the lasso button carries the clipboard mark, `FloatingSelectionBar.buttonAt` letting
the bar query its own latch state). Pen-tap paste via `StickyClip`. No shapes, text or stickies
inside a note — only `stroke` rows may be a sticky's children.

**A single-finger double-tap hides / shows the top bar (arc 33 / F2)** — the same gesture as the
other three paper screens, over the same shared pieces: `chromeToggle` (`:sn-screen`'s
`ChromeToggle`) over `listOf(topBar)`, `beforeHide = { hideEraserBar() }`,
`afterLayout = ::pushExclusions`; `chromeBand()` = `ChromeBand.of(root.height,
topBar.asBar(bottom), null)` feeding `EraserBar.bandBottom` and `FloatingSelectionBar.band`;
`apply(prefs.hidden, initial = true)` is applied right after the (now `root`-level) layout
listener, and the `onResume` re-sync runs before `resumeDrawing()` — another screen may have
flipped the one global, persisted `ChromePrefs` flag in the meantime. `pushExclusions()` is
`rectOf(topBar)` + the two floating bars (root → paper px) + the off-page bands below (already
paper px, taken from the `pageW`/`pageH` `showNote` captured). No collision rule is needed here —
unlike the notebook, nothing else answers a finger tap on this screen.

**A new sticky's content is the full window** (`StickyDefaults.contentSize(windowW, windowH)` —
the `topBarPx` parameter was **removed, not zeroed**: the note's content is the whole window, not
"window minus a bar"). **An existing (pre-arc-33) sticky lays out top-left** in the full-bleed
view at the size it was authored, with the band below its page — and, if it is narrower than the
window, the band to its right too — blocked from ink by pure `StickyPageRects.offPage(pageW,
pageH, viewW, viewH): List<Band>` (below full-width, right page-height, never overlapping;
`Band.toRect()` is the one Android line — `android.graphics.Rect` is a stub under
`isReturnDefaultValues`, which is why the pure type the rule is tested through is `Band`, not
`Rect`). g-paper leaves the area beyond the page white **and writable**, so this exclusion is the
only thing keeping ink inside the note. **Consequence, accepted like the calendar's (decision 5's
twin of decision 3):** an old note's ink sits one bar height higher than the ruling it was
authored against — nothing is moved or lost, hiding the chrome shows it plainly.

**Since arc 36 / C2 the editor collapses too**, over the same shared `:sn-screen` piece as the
other three paper screens (`CollapsedChrome` — see [`docs/sn-screen.md`](sn-screen.md)). While the
top bar is hidden a corner button sits at `top|end` wearing the armed tool's glyph; a tap opens a
mini toolbar of **Pen · Point eraser · Lasso eraser · Lasso · Back** — the note has one door, and
one overflow entry is not an overflow (`CollapsedTools.overflowInline`, `INLINE_MAX` 2), so Back
sits on the mini toolbar itself and there is no `…`. Back is mirrored from the top bar's own
button, so a tap on it closes the row and performs the bar's own click — saves and closes, exactly
as the bar's Back does. **The lasso wears the clipboard mark on the corner button and the mini
toolbar, as it does on the bar** (`syncClipboardMark()` also calls `collapsed.showClipboardLoaded`)
— the one standing hint that a pen tap on bare paper will paste, read wherever the lasso shows.
Picking a tool from the mini toolbar arms it through `toolbar.arm` (a host-set tool is never echoed
back as `onToolChanged`; the corner button repaints from `paper.tool` through `PaperToolbar`'s
`onSynced`, the one funnel every arm passes through) and closes the row; the corner button itself is built after the toolbar and the
eraser sub-bar, since a pick lands on `toolbar.arm` and opening the row takes the sub-bar down
first (`onOpen = { hideEraserBar() }`). `ChromeToggle`'s `whileHidden` is the corner button and its
`beforeShow` dismisses the row, exactly as the other three screens' toggles do.

**Since arc 29 / LE2 the eraser has two kinds here too**, reached the same way as the notebook's own
bar: a second tap on the armed eraser opens `:sn-screen`'s `EraserBar` (Point · Lasso) — the last
child of the editor's root, dismissed on every pointer-down outside the bar and its own eraser
button (`ACTION_DOWN` / `ACTION_POINTER_DOWN`), and hidden on `exit()` and on `reload()`'s content
swap. `onLassoErased` is exactly the point eraser's body — the note carries no content renderers at
all (no headings, no links, no other objects), so `contentIds` is always empty and ignored, and
there is no new `StickyInk.Action` kind for it to fall under.

**`StickyEditorTransfer`** — a process-local singleton, og's `persistToHost` pattern, because the
editor **opens no `.soil` of its own**: the notebook's `NotebookSession` stays alive behind it and
every row the editor writes goes through that session's one serial `SoilWriter` via a `Sink`
(`setContent`/`drain`) the notebook binds at launch. Three fields, each owned by one side at one
moment: `Showing` (staged by the notebook immediately before launch, taken **once** by the editor's
`onCreate` — ids, content size, the child strokes as already read, the sink); `output` (written by
the editor on its way out, read by the notebook's result callback); `stage`/`take`/`leave`/`clear`.
Writes are debounced (~600 ms) and flushed in `onStop`. **`take()` is once-only**, so an Activity
recreate (not just a process death) finishes empty-handed — accepted, since the debounce window is
at most what's lost.

`StickyInk` is the pure in-editor undo model: `Drew(stroke)` / `Erased(indexed: List<IndexedValue
<Stroke>>)` / `Moved(ids, dx, dy)` / `Pasted(strokes)` — index-faithful revert. `StickyClip` is
pure too: copy captures the note's stroke rows parented to the sticky id through
`ObjectClip.capture`; paste accepts **stroke rows only** from whatever the clipboard holds
(page-space first, a copied sticky's children only when there's no page ink to prefer), and
`leftOut` is measured against what was actually **dropped** — the editor tells the user when
something didn't paste. `StickyDefaults.at(...)` places the 72 dp icon; `contentSize(windowW,
windowH)` is computed by the **notebook**, not the editor, and is the whole window since arc 33 /
F2 (the editor's paper is full-bleed under a floating bar) — so the row is written complete on its
very first insert.

**`StickyFlow`** (+ `StickyFlow.Host`) owns insert and reopen: `insertAtCentre()`, `openAt(x, y):
Boolean` (finger-tap hit test — stickies checked **before** links, topmost first, so a sticky
sitting over a link opens the note), and `onEditorClosed()`. The close sequence, in order:
`reclaimPipeline()` called **first** (guarded on `::paper.isInitialized`, because the callback also
fires when the whole screen was rebuilt after a process death and `onCreate` bounced on
`IndexGuard`), then drain, then a re-read of the note's children, and **one**
`Action.StickyContentEdited(before, after)` recorded per showing **only if the content actually
changed** — `before` is the showing's initial read, `after` is the re-read (never the editor's
parting word, since a recreate could lose it).

**EPD handoff chain** (Z3's rule): `dismissFloatingChrome()` → `endTransformIfRunning()` →
`paper.releaseForHandoff()` → launch, all inside one page op after `drain()`. The editor's surface
calls `resumeDrawing()` in `onResume` and `releaseForHandoff()` before **every** `finish()`. The
notebook's `reclaimPipeline()` is the result callback's very first statement — result callbacks run
**before** `onResume`, so anything later would race a resumed pen.

`SelectionMode.STICKY` offers the base row (Snap/Copy/Cut/Delete, link-free Link) with **no bar
button of its own** — its verb is a finger tap on the icon, not a bar action. `TagSelection`
refuses it. **Process death**: `am kill` refuses a foreground process, so `am crash <pkg>` is the
door that actually exercises this path — the Bootstrap relaunch loses nothing but the unflushed
debounce window, since every earlier stroke is already a row.

## Selection, undo, clipboard, erase

`SelectionModes.classify` (`notebook/SelectionModes.kt`) is the pure `when` a test can read
directly:

| Mode | Snap·Copy·Cut·Delete | H | Text (convert) | Link | Edit | Transform | Tag | Pad·Calendar | Bible |
|---|---|---|---|---|---|---|---|---|---|
| `STROKES` | ✓ | ✓ | ✓ | ✓ | | | ✓ | ✓ | ✓ |
| `HEADING` | ✓ | ✓ | | ✓ | stylus tap | | ✓ | | |
| `TEXT` | ✓ | | | ✓ | stylus tap opens the dialog | | | | |
| `SHAPE` | ✓ | | | ✓ | | ✓ | | | |
| `STICKY` | ✓ | | | ✓ | finger tap opens the editor (no bar button) | | | | |
| `LINK` / `MIXED_WITH_LINK` | as today | | | | | | | | |
| `MIXED` | ✓ | | | ✓ (link-free) | | | | hidden if any new kind is inside | hidden |

The classify rule, in order: exactly one content object with no ink and no link → its own lone
mode; a link anywhere else in the set → `MIXED_WITH_LINK`; ink alone → `STROKES`; anything else →
`MIXED`. **Both Sends (Pad and Calendar) hide (GONE, never disabled)** whenever the selection holds
any of the three new kinds — the same rule that already hides them for non-ink content.

**Bible** (arc 38 / R3) is `STROKES`-only, added beside Pad and Calendar for the same reason those
two are: `SelectionToolbar`'s `onBible` reads this ink selection through the recognizer first (the
same `HeadingConvert.run` machinery H and Text already share) and hands the recognized line to
`BibleRefFlow.convert`, which offers it in the reference dialog for the user to correct before it
is resolved. It is gated on `isBibleAvailable()` — re-read on every `show()`, the pad's own rule —
but **narrower than "installed"**: the gate is `BibleEntry.supportsReferences`, a reader declaring
`MIN_API_VERSION_FOR_BIBLE_REFERENCE` (12), the **method** floor, not the action floor `IBible`
itself binds at (11). An 11-only reader still serves the plain door from the library and notebook
bottom bars, but this button — and the Insert bar's ninth kind, below — stay `GONE` against it,
because neither `resolve` nor `beginAt` exists on that reader to call.

`NotebookUndo.Action` gained six kinds and widened four existing ones:
`TextCreated(text, strokeIds)` (empty strokeIds for a plain insert), `TextEdited(before, after)`,
`ShapeInserted(pageId, shape)`, `ShapeTransformed(pageId, before, after)`,
`StickyInserted(pageId, sticky)`, `StickyContentEdited(stickyId, before: List<Stroke>, after)`; and
`Deleted`, `ScribbleErased`, `Moved`, `ObjectsPasted` grew `textIds`/`shapeIds`/`stickies` fields (a
sticky snapshot carries its children, the way a link's snapshot already did). Both replay `when`s
in `NotebookActivity` stay exhaustive — a new kind that misses one is a compile error, a widened
field that misses one is a silent no-op, and every new action is tested both ways. The rule is
unchanged: mutate the store → `drain()` → `refreshToPage(pageId)`; new rows revive **in place**.
`recordWithStickies` defers a sticky-holding delete's content snapshot (taken via
`StickyStore.withContent` **before** the row goes) into the same one-gesture undo entry.

**Arc 38 / R3 added two more, over the existing `link`/`text` machinery rather than a new row
kind**: `BibleRefCreated(pageId, link, text, strokeIds)` and `BibleRefEdited(pageId, before, after:
PageLink)`. A Bible reference is a text object wrapped in a link — three rows for a conversion (the
text, the link, the erased ink), two for an insert — and each act is **one** undo entry covering
all of them: undoing a `TextCreated` + `LinkCreated` composed separately would let the user undo
"half a reference" and be left with a plain text object that used to be a link, which is exactly
the outcome the arc's "one act, one undo step" rule exists to prevent. `BibleRefCreated`'s replay
unwraps, deletes the text and revives the ink **in place** (the writing-order rule — arc 3's
standing trap); `BibleRefEdited` carries the **whole** `PageLink` on both sides, each **carrying
its one wrapped text**, so a single snapshot pair is enough and neither side needs a separate id
list — replay writes the text's content, the link's payload and the link's re-derived box back
over the same three row ids in one pass.

**The invariant `BibleRefFlow.edit` leans on**: a Bible link wraps **exactly one text object and
nothing else** — by construction, since only this flow ever creates one. A link that does not
(hand-edited, imported, or corrupted) is a row this flow refuses to touch rather than guesses at:
`edit()` checks `link.texts.singleOrNull()` plus every other wrapped-kind list being empty before
opening the dialog, and explains itself (`bible_reference_unwrappable`) rather than crashing or
silently editing the wrong thing.

`ObjectClip.plan`'s `when (out.type)` gained the three kinds; `NotebookSession.ORDERED_TYPES` is
now `[stroke, heading, link, text, shape, sticky_note]` (6 entries); `captureObjects` reads a
sticky's children the way it reads a link's. A sticky's children keep **local** coordinates through
a paste — only the parent row shifts. `payloadBounds` for a shape uses the density-free
`ShapeGeometry.tightBounds` + `strokeWidth/2` (a payload is page px, not one screen's density — the
plan's original wording naming `aabb(shape, density)` was corrected by the H4 ledger). `PageClip`
needed no change (kind-agnostic already, verified by test); `NotebookRemap` still rewrites only
link payloads. The eraser, scribble-erase **and, since arc 29 / LE2, the lasso eraser** take a
**whole object** — a swept text, shape or sticky icon goes as one, never a part; the eraser never
reaches inside a sticky from the page. The lasso eraser's reach is the lasso's own hit rule
(`LassoHitTest.polygonIntersectsBounds`): a content object goes whole the moment the drawn loop
touches its box, exactly what the lasso already selects — so select-then-Delete and a lasso erase
always agree about what one loop holds. It is reported by the engine as one `onLassoErased(strokeIds,
contentIds)` and recorded as `NotebookUndo.Action.LassoErased` (`ScribbleErased`'s exact shape, its
own kind for the same label reason: drawing a loop around something is a different act to the user
than crossing it out or tapping Delete) — see [`docs/notebook.md`](notebook.md) § Undo for the row. **Erase page** (arc 30 / PE1) takes every kind too — texts, shapes, sticky notes and
their children, through the one `liveDescendantIds` query — and puts them all back on undo
(`Action.PageErased`, ids only); see [`docs/notebook.md`](notebook.md) § Erase page.

## Export — PDF endnotes

`PageBundle.VERSION` moved 1 → 2, **backward-readable**: a v2 `Reader` accepts a v1 stream (no
trailer, `readLinks()` returns empty); the `Writer` emits **v1 byte-for-byte whenever `links` is
empty**, so an older `:ext-pdf` still opens a sticky-free bundle without knowing v2 exists. The v2
trailer, after the pages: `int linkCount` (capped at `MAX_LINKS` = 65536), then per link `int
fromPage · float l t r b (px, from-page space) · int toPage` (1-based pages). `pageCount`
**includes** the endnote pages. Every link is validated in `PageBundle.Link`'s constructor
(non-empty finite rect) and checked against `pageCount` before any allocation.

`ExporterInfo` gained `bundleVersion: Int = 1` as a second compatible tail after `sourceKind`
(`dataAvail()`-gated read) — **no `API_VERSION` bump**. `PdfDescriptor.info()` declares
`bundleVersion = PageBundle.VERSION`. Facing a v1-only exporter with stickies present, the host
still exports — icons only, notes silently dropped — and the Export screen names it.

`:ext-pdf`'s `PdfLinks.annotations(links, pageHeights)` is the one pure conversion: 1-based →
0-based pages, vertical flip `lly = pageH − b`, `ury = pageH − t` (the PDF page is the notebook
page's own pixel size 1:1, so no scale). A link naming a page the heights list doesn't cover is
**refused**, never silently dropped. `PdfAssembly` reads the trailer after writing every page and,
only when it is non-empty, adds one borderless `PDAnnotationLink` + `PDActionGoTo` +
`PDPageFitDestination` per entry in a `"linking the endnotes"` stage — **before** `protect()` (the
password-protect option's existing ordering). With no stickies the bundle is v1, the trailer is
empty, the annotation pass never runs, and the PDF is byte-identical to before this arc — pinned by
test.

Host side: `SoilDao.stickyIdsWithContent()` is one notebook-wide JOIN — a note with no live stroke
gets no endnote at all. Pure `export/Endnotes.plan(sources, pageCount)`: numbering is page order
then z-order, loose notes before link-wrapped ones; a note's page = `pageCount + N`; content size
comes from the row's `flags`, falling back to the **source page's** size for an old/foreign row
with none, clamped to `MAX_DIMENSION_PX` with the 60 px caption strip added; two links per note
(icon rect → the note page, caption strip → the source page); a zero-area icon gets no icon link.
`ExportRender.render(..., bundleVersion)` plans the endnotes **before** the first page is written
(the writer needs the count and links up front), walks the pages, recycles the page template, then
bakes each note: strokes clipped to the content area, a 1 px rule, `Note N — from page P` in 32 px
sans black at a 16 px inset, encoded WEBP q100 like a page, one bitmap alive at a time.
`ExportActivity` passes `c.info.bundleVersion` through and reads `hasStickyContent` on the **same**
`readOnce` call as `hasDocument` (returned as a `Pair`) to avoid a second DB round trip. The
one-line `export_endnotes_unavailable` notice shows under the options exactly when
`ExportDocumentRules.endnotesUnavailable(sourceKind, bundleVersion, hasStickyContent,
documentSource)` is true — a v1 page exporter, notes with content to lose, and pages (not the
document) about to be drawn. Text export excludes sticky content entirely; `.soil` export carries
the rows verbatim, encrypted or not as the notebook already is.

## Failure table

| Situation | What happens |
|---|---|
| Recognition fails on lasso Text | The source ink is left untouched; nothing is created (the heading rule) |
| Blank Save / Cancel on the first Insert-bar text dialog | Nothing was ever written — no placeholder row, no minted id — so nothing is removed; nothing blank ever exists |
| A `beginTransform` is declined (id not adopted) | The host rolls back and re-selects; no `onTransformEnded` will come for it |
| A transform is interrupted (close / `onStop` / navigate / handoff / another bar shown) | `endTransformIfRunning()` fires at all eight sites, ending the mode exactly as Done would |
| The sticky editor dies mid-session (process death) or the Activity is merely recreated | `StickyEditorTransfer.take()` is once-only; the editor finishes with nothing staged, losing at most the unflushed ~600 ms debounce window |
| Clipboard paste into a note holds non-ink content | Only the stroke rows paste; `leftOut` is reported so the user knows something didn't come across |
| A note has no strokes at export time | `SoilDao.stickyIdsWithContent()` excludes it — no endnote page, no links |
| A v1-only page exporter faces a notebook with sticky content | The export proceeds — icons only, notes dropped silently on the PDF side — and the Export screen shows the one-line notice |
| An unknown `style` value on a shape row | `ShapeRows.toShape` returns null — the row is dropped, never a crash |
| A blank/missing `text` on a text row | `TextRows.toText` returns null — the row degrades to "not rendered", never a crash |
| An endnote's content is larger than `MAX_DIMENSION_PX` | The size is clamped; content taller than one page is **not** split (og's deferred item, kept deferred) |
| A rotated shape needs a hit test | The AABB is used, never the rotated outline (the accepted caveat) |

## Design calls recorded outside the wizard

- Text objects render at 24 sp regular; headings inside a text object's own Markdown scale as the
  document editor's do (`HeadingTypography.scaleFor`); lists/blockquotes/rules draw exactly as
  `MarkdownRenderer` already draws them elsewhere — nothing new added to `:markdown` this arc.
- A text object's `x`/`y` is its top-left and stays fixed through an edit; the box grows down and
  right; a paste clamp keeps it on the page.
- The Insert bar remembers nothing between showings; no tool is armed after an insert.
- Shape outline width is fixed at 3 px (the pen's) this arc — no width control.
- The sticky editor's paper is the notebook's white with **no template** — the note's content size
  is the only page it has.
- The sticky's finger-tap gate copies `LinkFollowFlow`'s thresholds exactly: single pointer, below
  the long-press duration, no move.
- Star outline is alternating outer/inner vertices from the top, inner ratio 0.5 (og's skip pattern
  was not copied); arrow arms are `min(0.3·width, 48 px)` at ±150°; a shape's AABB pad is
  `max(strokeWidth/2, 4 dp)`.
- `TextRenderer.measure` floors the wrap column at 48 px; `PageObjects` re-measures texts on every
  load and writes nothing back (position authored, size derived — N3's finding, restated).
- `InsertBar.shapeType(kind)` is the one routing table from an Insert-bar kind to a `ShapeType`,
  and it is tested.
- The lock button on `ShapeTransformBar` is styled field by field, not via a style resource — a
  style cannot be applied to a code-built view (`ExportPanel`'s earlier finding, re-used here).
- The sticky editor's paper sits **below** the top bar, not full-bleed under it, so the bar needs no
  exclusion rect; the content size is minted once by the notebook rather than written back by the
  editor; the editor's undo `after` is always the **re-read** row set, never the editor's own
  parting word (a recreate could lose that).

## Standing traps

- **Two exhaustive `when`s over `Action`** (undo and redo replay in `NotebookActivity`) — a new
  kind that misses one is a compile error; a widened field that misses one is a silent no-op. Test
  every new action both ways.
- **Raw kind-string SQL** in `SoilDao` (`liveContentIds`, `liveDescendantIds`) and `DocumentDao`
  (three whitelists) is edited by hand; `FakeSoilDao` must mirror each change.
- **`"order"` is per parent AND type** — a pasted set rebases after the destination's max, relative
  order preserved.
- **Renderer content must be set before `loadStrokes`** on a page load; never repaint from
  `onScribbleErased`; one Main block = one EPD frame.
- **ActivityResult callbacks run BEFORE `onResume`** — the sticky editor's `reclaimPipeline()`
  latches at the very top of the result callback, not in `onResume`.
- **`releaseRender()` must be gated on `!isPenActive`** in every bar handler except one that opens
  a dialog (a dialog opener is ungated on purpose — H2's fix).
- **A 1 dp hairline at fractional density is a coin flip** — always `round(density)` px on integer
  edges, for the transform overlay and the text box alike.
- **`Stroke.bounds` is point-tight** — a sticky's content bitmap and any clipboard-bounds union
  must grow by `strokeWidth/2`.
- **`StaticLayout` cannot be JVM-tested** under `returnDefaultValues` — measure logic that needs it
  stays thin and is walked, not unit-tested; the pure arithmetic around it is tested.
- **File tools can land a raw NUL byte** — byte-scan every changed doc/code file before calling a
  phase done.
- **Drain the shared `SoilWriter` before any capture/gather/raster** — including the endnote render
  and the sticky editor's read on open.
- **A `<shape>` stroke root with no padding hides every border** — `Dialogs.style` supplies the
  border `TextEditDialog` needs.
- **Raise the IME from `onWindowFocusChanged` behind a once-per-showing latch, explicit flag 0** —
  the text dialog on Ratta; hardware keys type only while the IME is shown there.
- **GONE, never disabled** for a control that has nothing to do (Pad/Calendar on a mixed selection,
  every Insert-bar button before its phase landed).
- **The engine commit lands with the host commit** — a pin at an uncommitted g-paper revision is a
  tree a fresh clone cannot resolve (H3's rule).
- **Check og's `drawable/` before drawing a "fresh" icon** — `ic_sticker_2`, `ic_convert_shape`,
  `ic_shape_*`, `ic_text_recognition` all already existed there.
- **Walk-agent false failures** — every walk in this arc was driven by hand on the Nomad; adb
  cannot lasso, drag, rotate, or ink, so an automated walk agent would only wander.
- **zsh globs a bare `====` echo separator** — quote shell one-liner separators (an H6 tooling
  trap, not a code trap, but it cost a round trip).
- **A capped endnote page must build its `PageBundle.Link` from the post-clamp size** — building it
  from the plan side before the size clamp made a capped page refuse its own caption link (H6).

## What the Nomad walks proved

- **H1**: sample rows (heading + wrapped paragraph text, all six shapes including a star at 37°,
  a sticky icon) render in D8 order under an existing link and survive close → reopen; the
  eight-button Insert bar fits in one row; both Sends hide over a lasso holding a sample text or
  shape.
- **H2**: insert / cancel / blank-save / lasso-convert-with-line-breaks-kept / a failed conversion
  leaves the ink / a stylus tap opens the dialog / a blank Save deletes / drag / wrap at the right
  edge / undo-redo ×3 / copy+paste across a page flip / survives close-reopen — all eight pass
  after the `armLassoForLanding()` fix.
- **H3** (on g-paper's own demo app): handles legible on the EPD, pen handle-drag with no firmware
  trail, free and locked corner resize, rotate-knob snap at the cardinals, body move, pen
  tap-outside exit, finger drag, finger tap-outside exit, tool-change exit — all eight pass. One
  finding on the demo's own finger handling led to the finger-gate rule that binds H4.
- **H4**: insert all six shapes, drag, handles+knob legible, aspect labels correct per type, a star
  at 37°, a line snapped vertical, tap-outside exit, undo/redo including a lock-only entry, fingers
  idle during the mode, eraser + scribble-erase, copy/paste across a flip, link-wrap/unlink,
  close-reopen, PDF renders stroke-only — all nine checklist items pass first time.
- **H5**: insert → editor opens, ink/erase/lasso-move/undo-redo inside the note, the icon lands
  selected, white interior shows over a template, finger reopens while stylus ink is unaffected,
  exactly one undo entry per showing, clipboard both directions plus the ink-only-content dialog,
  page-level parity (bar, copy/paste across a flip, eraser + scribble, link-wrap/unlink),
  close-reopen, PDF shows the icon only, and process death via `am crash` loses nothing but the
  debounce window (`am kill` alone does not touch a foreground process).
- **H6**: endnote pages and captions render, icon→note and caption→page links work both ways, a
  password-protected PDF keeps the links, a sticky-free notebook exports exactly as before, an
  empty note gets no endnote page, and the on-page render still shows icons only — all six items
  pass.

## Not built / backlog

- og's diamond, trapezoid, pentagon, hexagon and arch shapes — each is its own fresh decision, not
  a rejected idea.
- A star point-count control (fixed at 5 this arc).
- A shape outline-width control (fixed at 3 px, the pen's).
- Text objects feeding the notebook document seed / Contents — the document seed (arc 19) still
  reads only ink; a text object is not a heading and does not appear in the outline.
- Extension transfers for any of the three new kinds — Send to Scratch Pad / Calendar stays
  ink-only; `:ext-ink`'s `InkWire` was not widened.
- Line objects and the dwell-triggered shape recognizer — both explicitly out by user decision,
  "that never worked well" (og's own recognizer was disabled too).
- og-byte-compatibility for these three row shapes — not a goal this arc, the way it wasn't for
  headings or links against *Paper* either (Paper has none of these rows at all).

## Tests

| Phase | `:app` running total | New this phase |
|---|---|---|
| (before arc) | 1194 | — |
| H1 | 1337 | +143 — `TextRowsTest`, `ShapeRowsTest`, `ShapeFlagsTest`, `ShapeGeometryTest`, `StickyRowsTest`, `StickyFlagsTest`, `TextStoreTest`, `ShapeStoreTest`, `StickyStoreTest`, `FamilyConstantsTest` (the three literals), `ObjectClipTest` / `PageClipTest` / `NotebookUndoTest` arms |
| H2 | 1369 | +32 — `TextLinesTest`, `SelectionModesTest`, `TextPlacementTest`, `TagSelectionTest` TEXT row |
| H3 | 1369 | +0 in `:app` (engine-only phase); g-paper core suite 193 green, `:sn-screen` 69 |
| H4 | 1393 | +24 — `ShapeDefaultsTest`, `ShapeBoxTest`, `ShapeTransformLabelsTest`, `InsertBarKindsTest`, SHAPE rows in `SelectionModesTest`/`TagSelectionTest`, a lock-only `ShapeTransformed` in `NotebookUndoTest`, a rotated star through `ObjectClipTest` |
| H5 | 1459 | +66 — `StickyInkTest`, `StickyClipTest`, `StickyDefaultsTest`, `StickyEditorTransferTest`, `SelectionModesStickyTest`, `TagSelectionStickyTest`, `NotebookUndoStickyTest`, `StickyStoreSetContentTest` |
| H6 | 1470 | +11 — `PageBundleTest` (11), `EndnotesTest` (8), `ExportRenderEndnotesTest` (2), `ExportDocumentRulesTest` +1, `PdfLinksTest.noLinksMeansNoAnnotations` |

**2830 tests across all modules at H6, all green; `:sn-screen` 69.** Every phase's tests were pure
Kotlin/JVM except where `StaticLayout`/`PaperView`/a real Binder made that impossible — those paths
were walked by hand on the Nomad instead, never left untested by any means.

**Arc 33 / F2** added `StickyPageRectsTest` (8, new) and rewrote `StickyDefaultsTest` (still 10)
for the full-window `contentSize(windowW, windowH)` signature — see `FOCUS_PLAN.md` for the rest
of that arc's numbers (`:app` 1614 → 1622 at F2).

## Related

- `docs/notebook.md` — the notebook screen: tools, selection, the Insert bar's place on the top
  bar, undo/redo, gestures.
- `docs/clipboard.md` — the clipboard: `ObjectClip`'s three new arms and sticky-children capture.
- `docs/export.md` — the Export screen: `PageBundle` v2, `bundleVersion`, the endnotes notice.
- `docs/extensions.md` — the seam: the `bundleVersion` compatible tail, the boundary audit.
- `docs/links.md` — link objects: how a link wraps the three new kinds.
- `docs/document.md` — Documents: the staleness whitelist growing the three new kinds (a note's
  inner strokes deliberately excluded).
- `docs/sn-screen.md` — the shared paper-screen library `FloatingSelectionBar.buttonAt` lives in,
  and (arc 29 / LE2–LE3) the `EraserBar` / `AnchoredBar` the eraser re-tap sub-bar is built from.
- `OBJECTS_PLAN.md` — the arc's full ledger: phase-by-phase Outcome entries, every phase-start
  question and answer, and the planner calls recorded along the way. Kept as history; this file is
  the reference going forward.
- `docs/notebook.md` (arc 29 "Loop") + `LOOP_PLAN.md` — the lasso eraser as a whole: the engine
  tool, the eraser re-tap sub-bar on all four paper surfaces, and `NotebookUndo.Action.LassoErased`.
