# Notesprout Paper — Project Intelligence

Paper is an experimental from-scratch rebuild of Notesprout. It keeps the `.soil` container family,
global-index model, global encryption model, and e-ink design philosophy — and drops everything else.

- **Branch:** `paper`
- **Location:** `apps/notesprout_paper/`
- **Plans:** `PAPER_PLAN.md` (v0, complete — architecture + locked decisions), then
  `PAPER_EXTENSIONS_PLAN.md` (arc 1, complete + frozen: extension API v1 + Templates extension), then
  `PAPER_NAMING_PLAN.md` (arc 2, complete + frozen: the host-owned encrypted extension store + the
  Naming extension), then `PAPER_RECOGNITION_PLAN.md` (arc 3, complete + frozen 2026-08-17: the
  engine-neutral `HANDWRITING_RECOGNIZER` capability point + the ML Kit extension `NSE · ML Kit`,
  debug-only "Recognize page" test surface), then **`PAPER_OBJECTS_PLAN.md` (arc 4, in progress —
  H0 ✅ 2026-08-17: content objects in the `.soil` + the selection toolbar with its
  extension-contribution API + the `MARKDOWN_RENDERER` capability point / `NSE · Markdown` + the
  generic `OBJECT_PROVIDER` point / `NSE · Heading`, the two proxies, g-paper 0.1.1)** — read all
  five top-to-bottom at the start of every session; **arc 4 is the active plan — start at its next
  `⬜` phase after the phase-start question ritual**
- **Package / applicationId:** `com.symmetricalpalmtree.notesprout` (debug: `.dev` suffix)
- **Launcher label:** "Notesprout Paper" (debug: "Notesprout Paper Dev")

---

## Standing rules

All rules from the root `CLAUDE.md` apply (language, serialization, no new deps, no Material, no
runBlocking on UI, IndexGuard, Slog, encryption hygiene, design system). In addition:

- **g-paper** is the drawing surface, consumed from **mavenLocal**
  (`com.symmetricalpalmtree.gpaper:gpaper-{core,onyx,ratta}:0.1.0`). Read g-paper docs before touching
  the notebook screen: `~/git/g-paper/docs/api.md`, `host-responsibilities.md`, `integration-guide.md`,
  and `~/git/g-paper/CLAUDE.md`.
- **No file over ~800 lines** without a written reason.
- **Portrait-locked** on every screen.
- **No colour in chrome** — ink itself is black in v0.
- **One layout per screen** — no width-variant XML files unless the narrowest device (MIP11) can't fit.
- **Every SQLCipher open goes through `crypto/SoilCrypto`** (non-destructive factories; opens are
  exists-guarded; creation only via the named create entry points). Read `docs/crypto.md` and
  `docs/data.md` before touching `crypto/` or `data/`.
- **Notebook screen** (`notebook/`): read `docs/notebook.md` first. The paper is full-bleed and chrome
  overlays it — every chrome rect goes to `setExclusionRects`; no app frame while `paper.isPenActive`
  (route chrome text changes through `whenPenIdle`); all `.soil` writes go through `StrokeStore`'s
  serial writer; the file is opened by `NotebookSession.open()` only (exists-guarded — never created
  there) and closed only by the `close()` sequence (cover → lastOpened → meta → seal). `GPaper` is in
  `com.symmetricalpalmtree.gpaper.core.engine`.
- **mavenLocal can lag the g-paper checkout** — if a g-paper symbol from `docs/api.md` is unresolved,
  `cd ~/git/g-paper && ./gradlew publishToMavenLocal` before suspecting anything else.
- **Extensions** (`PAPER_EXTENSIONS_PLAN.md`, `docs/extensions.md`): the project has six modules —
  `:app` (the core/host), `:extension-api` (the shared contract library), `:ext-templates` (the
  first-party Templates extension APK), `:ext-naming` (the Naming extension APK, arc 2),
  `:ext-mlkit` (the ML Kit handwriting-recognizer extension APK, arc 3) and `:ext-markdown` (the
  Markdown renderer extension APK, arc 4 / H0).
  **`:extension-api` depends on nothing in `:app`, ever** (Gradle enforces `:app → :extension-api`,
  `:ext-templates → :extension-api`, `:ext-naming → :extension-api`, `:ext-mlkit → :extension-api`,
  `:ext-markdown → :extension-api`; `:app` and the extension modules never depend on each other). Extension-side caller check = `HostCallerCheck.enforce(ctx,
  BuildConfig.HOST_PACKAGE)` from `:extension-api`, first thing in every stub method. An extension is a separate APK with **no launcher Activity**, bound over
  AIDL; the core trusts it only if `checkSignatures == SIGNATURE_MATCH` (in dev, the shared
  `~/.android/debug.keystore` satisfies this). **Naming + icon convention:** label `NSE · <Name>`
  (debug appends ` Dev`), icon = Tabler `puzzle` black outline; the app's own icon is the bare Tabler
  `seedling` (both sized like Ratta's icons — Supernote lists every installed package, launcher or not;
  accepted). Details: `docs/extensions.md` §"Naming + icon convention".
- **Notebook creation:** templates come **only** from `ExtensionRegistry` providers via
  `TemplateProviderClient` (bind-per-operation, signature re-checked at bind, timeouts, unbind in
  `finally`, payload = mime + byte cap + exact requested size); **the core has no renderer**. No
  extension → no Template section, blank notebook. A render failure stays on the screen with a toast —
  never a silent Blank. Identity labels: `BLANK` | `<pkg>:<id>` | legacy `LINED`/… .
- **Extension store** (arc 2 / N0, `data/extstore/`, `docs/extensions.md` §"The extension store"): an
  extension's data lives in a **core-owned** SQLCipher key/value DB `Garden/<pkg>.db` under the global
  key (raw-key id `ext:<pkg>`), opened only by `ExtensionStores.open` (open-or-create — the **third
  named create entry point**, `docs/crypto.md` audit item 2) and reached by the extension only through
  an `ExtensionStoreBinder` the host mints **per bind, uid-bound, revoked in the unbind `finally`**;
  caps `ExtensionContract.STORE_*` (512 chars / 256 KiB / 50 000 keys). Never a key, path, or `File`
  across the boundary. Open the store on IO **before** binding (cold KDF must not sit inside the call
  timeout). The `.db` survives the extension's uninstall.
- **NotebookNamer** (arc 2 / N1, `docs/extensions.md` §"NotebookNamer — host behaviour"): the second
  point (`ACTION_NOTEBOOK_NAMER`, `INotebookNamer`, `SchemeField`). `ExtensionRegistry.notebookNamer`
  returns **the first** trusted namer; `NamerClient` = the Templates client shape + store pre-open,
  per-bind `ExtensionStoreBinder`, revoke in the unbind `finally`, every call ≤ 2 s. `LibraryActivity`
  owns the three entry points (New-folder scheme field · folder long-press "Default notebook name…" ·
  +Notebook prefill resolved **before** `NewNotebookActivity` opens via `EXTRA_DEFAULT_NAME`) and all
  are **absent when no namer is installed**; `NewNotebookActivity` stays extension-agnostic
  (`acceptDefaultName` = name rule + `MAX_NAME_CHARS`). Outward payload = folder UUID + sibling
  notebook names (+ the typed scheme) — the one recorded widening of the boundary rule; the core never
  interprets a scheme.
- **HandwritingRecognizer** (arc 3 / M0, `docs/extensions.md` §"HandwritingRecognizer (contract)" +
  §"The ML Kit extension"): the third point (`ACTION_HANDWRITING_RECOGNIZER`, `IHandwritingRecognizer`,
  `InkStroke`, `RecognizerStatus`) is a **capability point** — engine-neutral, stateless, `en-US` only;
  the core binds it (M1) and later lends it to consumer extensions through a proxy, never
  extension-to-extension. Outward = bare x/y geometry + area/page size + ≤ 20 chars pre-context (the
  recorded widening of the boundary rule for this point); caps `MAX_INK_STROKES 2 000` /
  `MAX_INK_POINTS 60 000` host-enforced before the call and re-checked by the extension.
  **`com.google.mlkit:digital-ink-recognition` lives in `:ext-mlkit` only** — never `:app` or
  `:extension-api`. The model lives in the extension's own sandbox (the recorded exception to
  "extension data goes to the host store" — engine assets are not user data). `status()` →
  `prepare()` → `recognize*` (M1: `status()` never blocks; `recognize*` waits for an acquisition
  already in flight inside the host's timeout and throws `IllegalStateException` only if it can't —
  M2: with the exact message `ExtensionContract.RECOGNIZER_NOT_READY`; **only `prepare()` may start a
  download** — the host asks first; the extension stops at its own budget just under the host's
  timeout). Host side (M1): **one shared
  bind path `ExtensionBinder.call`** for all three clients; `RecognizerClient` + `InkCaps` (caps before
  the bind); the notebook debug ⋯ owns the model-download dialog flow (offline pre-check via
  `core/Connectivity`). **Recognized text is never logged on either side** — counts + durations only.
  M2 froze it: boundary-audit rows 14–17 (`docs/extensions.md`), the capability-point rules 12–17 +
  §"The capability pattern" (the proxy recipe — `RecognizerProxyBinder`, built only with the first
  consumer point, never before), and the "Writing an extension" recognizer paragraph. **Dots** (M2
  addendum): the ML Kit extension rounds tiny strokes and forces a trailing baseline dot to `.` — see
  `docs/extensions.md` §"The ML Kit extension"; it is the extension's heuristic, the core knows
  nothing of it. **Logging in
  extension modules:** `Slog` lives in `:app` and is unreachable from `:ext-*`; the recorded
  equivalent there is `if (BuildConfig.DEBUG) Log.d(...)` (never text — counts + durations).
- **MarkdownRenderer** (arc 4 / H0, `docs/extensions.md` §"MarkdownRenderer (contract)" + §"The
  Markdown extension"): the fourth point (`ACTION_MARKDOWN_RENDERER`, `IMarkdownRenderer`,
  `RenderedImage`) is the second **capability point** — markdown in, a transparent lossless-WEBP
  image out (`RenderedTemplate` handshake + declared `widthPx × heightPx`, verified by the host);
  blank source → `null`. Caps `MAX_MARKDOWN_CHARS 20 000` / `MAX_IMAGE_EDGE_PX 4 096` /
  `RENDER_PADDING_MAX_PX 64`. **All typography lives in the extension** (`MarkdownSpans` /
  `MarkdownBitmap`: base 24 sp, bold headings ×2.0/1.75/1.5/1.25/1.1/1.0) — the core never learns a
  font size. Nothing in `:app` binds it until H3 (`MarkdownClient` + `MarkdownProxyBinder`). The
  markdown text is never logged on either side.
- **Toast vs. dialog:** a toast only confirms something that already happened ("copied"); anything
  the user must notice or act on — why a tap did nothing, a failure, a one-time download — is an
  `AlertDialog` (e-ink: a toast is easy to miss and reads as "broken"). The one helper is
  `Dialogs.problem(activity, title, message)` (`core/Dialogs.kt`) — use it, don't hand-roll; the
  library, New-notebook, folder-picker and notebook-debug screens all go through it (M2 swept the
  arc-2 namer / name / move toasts).
- **Extension boundary (frozen in E2):** nothing but what a call needs crosses outward (templates: id +
  page geometry + dpi — never keys, paths, ids, names, strokes); everything inward is untrusted. Adding
  an extension point follows `docs/extensions.md` §"Rules for adding a future extension point" and adds
  its rows to §"Boundary audit". `:ext-templates` depends on `:extension-api` only — it is the
  third-party reference implementation; keep it that clean.
- **Notebook screen "Opening…" popup** (`openingOverlay`) is visible from the first frame and hidden
  only when `opened` is set — do not hide it earlier, and do not gate the hide on the pen (hover counts
  as active). While it is up the whole paper is an exclusion rect (no pen input); `pushExclusions()`
  restores the chrome rects at the same moment.

## Build & install

```sh
cd ~/git/Notesprout/apps/notesprout_paper
./gradlew assembleDebug                  # all modules → app + ext-templates + ext-naming + ext-mlkit + ext-markdown debug APKs
./gradlew testDebugUnitTest              # all modules
adb -s SN078D10012852 install -r app/build/outputs/apk/debug/app-debug.apk   # SNN (Nomad)
adb -s 92c16533       install -r app/build/outputs/apk/debug/app-debug.apk   # NA5C
adb -s 5HL21V5007384  install -r app/build/outputs/apk/debug/app-debug.apk   # MIP11
# The extensions (install alongside the app on the same device):
adb -s <serial> install -r ext-templates/build/outputs/apk/debug/ext-templates-debug.apk
adb -s <serial> install -r ext-naming/build/outputs/apk/debug/ext-naming-debug.apk
adb -s <serial> install -r ext-mlkit/build/outputs/apk/debug/ext-mlkit-debug.apk      # ~40 MB; the en-US model (~20 MB) downloads on first prepare() — Wi-Fi once per device
adb -s <serial> install -r ext-markdown/build/outputs/apk/debug/ext-markdown-debug.apk # ~2.5 MB; the Markdown renderer (arc 4)
adb -s <serial> shell pm enable com.symmetricalpalmtree.notesprout.ext.templates.dev  # BOOX sideload trap — BOOX may
adb -s <serial> shell pm enable com.symmetricalpalmtree.notesprout.ext.naming.dev     #   re-disable a few seconds AFTER
adb -s <serial> shell pm enable com.symmetricalpalmtree.notesprout.ext.mlkit.dev      #   install; re-run enable and confirm with `pm list packages -d`
adb -s <serial> shell pm enable com.symmetricalpalmtree.notesprout.ext.markdown.dev
```

Debug launch: `adb -s <serial> shell am start -n com.symmetricalpalmtree.notesprout.dev/com.symmetricalpalmtree.notesprout.bootstrap.BootstrapActivity`
(BootstrapActivity is the launcher and the only thing that opens the index; every other screen
bounces there via `IndexGuard`.) The debug build's library ⋯ menu has "Show recovery key" and
"Forget cached key" (kills the process → next launch is the Unlock screen); the notebook screen's
debug ⋯ has "Recognize page (ML Kit)" (present only with `NSE · ML Kit` installed). The app's files are
readable from `adb shell` at `/sdcard/Android/data/<appId>/files/` (index + `Garden/`).

BOOX trap: `install -r` can leave the package disabled → `pm enable com.symmetricalpalmtree.notesprout.dev`.

## Test devices

| Nickname | Device | Serial | Engine |
|---|---|---|---|
| SNN | Supernote Nomad | `SN078D10012852` | `gpaper-ratta` |
| NA5C | BOOX NoteAir5C | `92c16533` | `gpaper-onyx` |
| MIP11 | Wacom Movink Pad 11 | `5HL21V5007384` | `gpaper-core` |

## g-paper version

Currently pinned: **0.1.0**. If a phase bumps g-paper, update the version in `app/build.gradle.kts`
and record it in the phase outcome.
