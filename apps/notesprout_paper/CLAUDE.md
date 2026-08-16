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

## Build & install

```sh
cd ~/git/Notesprout/apps/notesprout_paper
./gradlew :app:assembleDebug            # → app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest
adb -s SN078D10012852 install -r app/build/outputs/apk/debug/app-debug.apk   # SNN (Nomad)
adb -s 92c16533       install -r app/build/outputs/apk/debug/app-debug.apk   # NA5C
adb -s 5HL21V5007384  install -r app/build/outputs/apk/debug/app-debug.apk   # MIP11
```

Debug launch: `adb -s <serial> shell am start -n com.symmetricalpalmtree.notesprout.dev/com.symmetricalpalmtree.notesprout.library.LibraryActivity`

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
