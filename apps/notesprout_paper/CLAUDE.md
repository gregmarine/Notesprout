# Notesprout Paper — Project Intelligence

Paper is an experimental from-scratch rebuild of Notesprout. It keeps the `.soil` container family,
global-index model, global encryption model, and e-ink design philosophy — and drops everything else.

- **Branch:** `paper`
- **Location:** `apps/notesprout_paper/`
- **Plan:** `PAPER_PLAN.md` — read top-to-bottom at the start of every session
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

## Build & install

```sh
cd ~/git/Notesprout/apps/notesprout_paper
./gradlew :app:assembleDebug            # → app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest
adb -s SN078D10012852 install -r app/build/outputs/apk/debug/app-debug.apk   # SNN (Nomad)
adb -s 92c16533       install -r app/build/outputs/apk/debug/app-debug.apk   # NA5C
adb -s 5HL21V5007384  install -r app/build/outputs/apk/debug/app-debug.apk   # MIP11
```

Debug launch: `adb -s <serial> shell am start -n com.symmetricalpalmtree.notesprout.dev/com.symmetricalpalmtree.notesprout.bootstrap.BootstrapActivity`
(BootstrapActivity is the launcher and the only thing that opens the index; every other screen
bounces there via `IndexGuard`.) The debug build's library ⋯ menu has "Show recovery key" and
"Forget cached key" (kills the process → next launch is the Unlock screen). The app's files are
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
