# Link objects (arc 7 — `NSE · Links`)

**Frozen at L5 (2026-08-20).** The one-stop reference for Paper's link objects: what lives where,
the full failure matrix, and every recorded deviation from the original Notesprout implementation
(the monorepo-root `docs/links.md` — inspiration, not a spec). The deep detail lives beside the
subsystems and is not repeated here:

| What | Where |
|---|---|
| The `LINK_PROVIDER` contract (AIDL, parcelables, caps, timeouts, the catalog binder, the payload grammar) | `docs/extensions.md` §"LinkProvider (contract)" |
| The `:ext-links` extension + picker screen | `docs/extensions.md` §"The Links extension" |
| Host behaviour (pick flow, create-in-picker, follow, walk-back) | `docs/extensions.md` §"LinkProvider — host behaviour" |
| Boundary audit rows 33–37 · rules 28–31 (§"Adding a navigation point") · "Writing an extension" item 12 | `docs/extensions.md` |
| The screen side (collaborators, render, toolbar, eraser, parity, gestures, undo table) | `docs/notebook.md` §"Link objects" + §"Gestures" |
| The `.soil` link row + re-parented children | `docs/data.md` §"Link rows" |
| The arc's plan, phase outcomes, wizard answers, device runs | `PAPER_LINKS_PLAN.md` (frozen) |

## The split of ownership

- **The core owns link *structure*.** A link is a core `.soil` row (`TYPE_LINK`, no version bump —
  additive) wrapping a lasso selection's **re-parented child rows**. The core wraps, unwraps,
  builds the composite render, gives links full lasso / move / delete / undo parity, detects the
  follow gestures, and performs **all navigation** itself.
- **`NSE · Links` owns link *meaning*.** The row's `text` payload is opaque to the core — written
  by the extension's picker, decoded only by the extension: `resolve(payload)` → a typed
  `LinkDestination` at follow time, `chromeOf(payloads)` → the underline flag at page load
  (session-cached; nothing extension-derived is persisted). The back-trail lives in the
  extension's host-owned store behind the point's trail methods.
- **With the extension uninstalled/disabled:** links render their content (no underline), still
  move / delete / **Unlink**; a follow tap gets the honest `links_required` dialog; swipe-up is
  silent; Back in a via-link notebook closes to the library; Link / Edit never appear.

## The user surface

- **Create:** lasso any mix of ink + objects (never another link) → **Link** → the picker (three
  modes: *This notebook* · *Notebook* — opens on its last-open page · *Notebook page*; style
  Underline / None, default Underline; create-in-picker: new page with an insert-before/after
  anchor sheet, new folder, new notebook through the host's real New-notebook screen). One undo
  step; picker-created targets are **not** undoable (a current-notebook page create also clears
  the undo stack — older structural snapshots predate the page).
- **Edit** (one selected link): the picker reopens pre-populated; OK patches the payload only —
  one undo step; unchanged payload = no-op. **Unlink** (never extension-gated) dissolves the link
  back to loose content.
- **Follow:** a bare **finger tap** — never the stylus; the pen always writes, even over a link.
  Same-notebook targets navigate in place; cross-notebook targets seal this notebook and relaunch
  into the target (the target's "Opening…" popup is the only feedback; re-taps are busy-guarded).
- **Back along the trail:** one-finger **swipe-up**, and **both Backs** (top bar + system) of a
  notebook opened via a link. Every follow pushes the origin page (same-notebook included); dead
  entries are skipped silently; an empty trail = swipe silent / Back closes to the library. The
  trail persists across force-stops (host-owned store) and is cleared by any fresh open from the
  library / recents.
- **Chrome:** the 1 dp underline, or nothing. Drawn live from the session chrome map — a style
  edit repaints without a composite rebuild.

## Failure matrix

| Situation | What the user sees | Where |
|---|---|---|
| Follow tap, no extension | "This link needs the NSE · Links extension." (`links_required`) | `LinkFlow.followAt` |
| Payload unresolvable (malformed, unknown future version) | "This link's target no longer exists." (`links_target_gone`) | `resolve` → null → `LinkFlow.follow` |
| Dead target — notebook not alive in the index, or a foreign page gone (read-only pre-check **before** leaving) | `links_target_gone`; no navigation, no trail push | `LinkFlow.follow` / `foreignPageAlive` |
| Self-target `DEST_NOTEBOOK` (untrusted payload; the picker never composes one) | Silent no-op | `LinkNav.planFollow` |
| Extension not answering at follow (bind/call failure) | "The Links extension didn't respond." (`links_picker_gone`) | `LinkFlow.follow` |
| Picker won't open (extension disabled meanwhile, bind failed) | `links_picker_gone` + re-discovery retracts Link / Edit | `LinkFlow.launchPick` |
| Picker result refused (malformed `LinkChoice` — over-cap / blank payload) | "…couldn't create the link." (`links_choice_invalid`) | `LinkClient.takeChoice` unmarshal → `LinkFlow.onResult` |
| Host recreated mid-showing (process death; launcher survives, client didn't) | `links_result_lost`; nothing applied | `LinkFlow.onResult` |
| Extension killed mid-showing | Picker vanishes with its process; silent cancel, notebook intact | verified live (L2, SNN) |
| Page at the link cap | "…page is full." (`links_page_full`) before any bind | `LinkFlow.beginCreate` |
| Catalog refusal in the picker (bad/duplicate name, dead parent/anchor) | The host's own user-honest `IllegalArgumentException` text, verbatim; prompt + typed text kept | `LinkCatalogBinder` create half → picker `failed()` |
| Catalog internal failure (`IllegalStateException` — incl. the `catalog:` rethrow) | The extension's generic failure text, never the raw message (L5 fix) | picker `failed()` |
| Showing revoked under the picker (`SecurityException`) | Picker finishes plain | picker `failed()` |
| Dead trail entry on swipe-up / Back | Skipped silently, next entry popped (cap 50); empty → silent / close-to-library | `LinkFlow.walkBack` |
| Trail push fails | Logged; the follow proceeds (one hop fewer to walk back) | `LinkFlow.pushTrail` |
| Dead `EXTRA_INITIAL_PAGE_ID` at arrival (race) | Falls back to the notebook's own last-open page, silently — the pre-checks before leaving carry the honesty | `NotebookActivity` |
| Wrapped child object's provider absent | The child draws as the dashed placeholder **inside** the composite; the link stays whole | `LinkComposite` |

## Recorded deviations from the original app

| Area | Original | Paper (and why) |
|---|---|---|
| Chrome | Dashed box + chevron icon, underline, or none | **Underline or none only** (user scope call); drawn live from a session map, never persisted (`flags` null — the heading precedent, L0 Q4) |
| Wrapped content | Child objects copied under the link (id churn — the UNIQUE-collision bug family) | **Re-parent, not copy** (L1 Q1): `parentId` flips page → link in one transaction; ids + page-absolute coordinates untouched; unlink flips back; no id churn possible |
| Semantics owner | Fully in-app | **The extension owns meaning** (arc Q2): opaque payload, `resolve` / `chromeOf` descriptions, structure survives the extension |
| Back-trail | SharedPreferences | **The extension's host-owned store** (arc Q5), key `trail`, cap 50, persisted across force-stop; cleared on any fresh open |
| Eraser | Links content-immune to scribble-erase | **The eraser erases whole links *and* whole objects** (L1 Q2 — the user's call, beyond both recommendations; g-paper 0.1.4 `onContentErased`); scribble-erase stays content-immune (off in Paper) |
| Follow gesture | Tap (stylus or finger) | **Finger only** (arc Q4) — the pen always writes |
| New notebook in the picker | In-picker create call | **The host's real New-notebook screen** (L3 Q2), templates included, over the `LinkCreateRelay` — nothing rides the Intent |
| Targets | Also file / website / URI (later phases) | Not built (deferred with page thumbnails, picker search, warm-bind — see the plan's Deferred list) |
| Warm-bind at open | n/a | **Measured, not built** (L4 Q4): warm tap→resolve 14–76 ms across the fleet — the chrome refresh at page load keeps the extension process warm in practice |

## Traps (carried forward)

- The picker and every create flow are **lasso-gated** — adb cannot drive them; device agents cover
  install / refusals / regressions, the rest is a by-hand checklist.
- A picker page-create in the current notebook **clears the notebook's undo stack** on return —
  recorded consequence, not a bug.
- Android redelivers the original Intent on recreation: a process death in a via-link notebook
  re-applies `EXTRA_INITIAL_PAGE_ID` (accepted, recorded in L4).
- The `LinkCreateRelay.prepared` slot is deliberately sticky (screen recreation must re-find it);
  it empties only on re-prepare / the showing's revoke.
- MIP11 resets `log.tag.*` to `I` — `setprop log.tag.LinkFlow DEBUG` before reading follow timings.
