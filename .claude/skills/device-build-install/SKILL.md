---
name: device-build-install
description: Build, sign, and install the Notesprout Android app (debug/release) to physical BOOX/tablet devices by nickname (G10, MAX, NA5C, etc.); includes the gradle/apksigner commands and the full device-serial + tier table. Use whenever asked to build, install, or sideload the app, or to look up a device's ADB serial.
---

## Build Variants & Install

- **Debug** (`com.notesprout.android.dev`) — active dev; installs alongside stable. **Default — always build/install debug unless told otherwise.**
- **Release** (`com.notesprout.android`) — stable; release installs are always explicit.

```sh
# Debug → app/build/outputs/apk/debug/app-debug.apk
cd apps/notesprout_android && ./gradlew assembleDebug

# Release (unsigned — must sign before sideloading)
cd apps/notesprout_android && ./gradlew assembleRelease
~/development/android-sdk/build-tools/35.0.0/apksigner sign \
  --ks ~/.android/debug.keystore --ks-pass pass:android --key-pass pass:android \
  --ks-key-alias androiddebugkey \
  --out app/build/outputs/apk/release/app-release-signed.apk \
  app/build/outputs/apk/release/app-release-unsigned.apk

adb -s <serial> install -r <apk-path>
```

Install all requested devices in a single shell block. If the user says devices are ready, **skip
`adb devices`** — go straight to build and install. Users refer to devices by nickname (e.g. "G10").

### Device Serials & Tiers

| Device | Serial | | Device | Serial |
|---|---|---|---|---|
| BOOX NoteAir5C (NA5C) | `92c16533` | | BOOX Go Color 7 (GC7) | `98d56306` |
| BOOX Note Max (MAX) | `6325773d` | | BOOX NoteAir4C (NA4C) | `1d36f870` |
| BOOX Go 10.3 (G10) | `34E517F9` | | BOOX Tab XC (TXC) | `d852bed0` |
| BOOX Go 6 Gen II (G6) | `DAF86F61` | | BOOX Go 7 (G7) | `17845014` |
| BOOX Palma2 Pro (P2P) | `287d2364` | | Wacom Movink Pad 11 (MIP11) | `5HL21V5007384` |
| BOOX Go 10.3 Gen 2 (G102) | `b7a46e13` | | Supernote Nomad (SNN) | `SN078D10012852` |
| Paper 7 (P7) | `T1737BBR0327` | | Supernote Manta (SNM) | `SN100C10023972` |
| Samsung Galaxy S26 Ultra (S26U) | `R3GL307HGDH` | | | |

> ⚠️ **The Supernote Manta reports itself as a Nomad.** Every `ro.product.*` property is identical
> across the two (`manufacturer=Supernote`, `model=Supernote Nomad`), and they run the same firmware
> build. **The serial is the only reliable way to tell them apart** — always pass `-s`, never trust
> `adb devices` model strings. On-device they differ only by resolution: Nomad 1404×1872, Manta
> 1920×2560, both at density 300. Note also that `Build.MANUFACTURER` is `"Supernote"`, **not**
> `"ratta"`.

---

## Paper (experimental rebuild)

Paper lives in `apps/notesprout_paper/` with its own Gradle project. See `apps/notesprout_paper/CLAUDE.md`.

- **applicationId:** `com.symmetricalpalmtree.notesprout` (debug: `com.symmetricalpalmtree.notesprout.dev`)
- **Launcher label:** "Notesprout Paper" (debug: "Notesprout Paper Dev")

```sh
cd ~/git/Notesprout/apps/notesprout_paper
./gradlew :app:assembleDebug            # → app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest
adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <serial> shell am start -n com.symmetricalpalmtree.notesprout.dev/com.symmetricalpalmtree.notesprout.bootstrap.BootstrapActivity
```

**Paper test devices** (only these three unless told otherwise):

| Nickname | Device | Serial | Engine |
|---|---|---|---|
| SNN | Supernote Nomad | `SN078D10012852` | `gpaper-ratta` |
| NA5C | BOOX NoteAir5C | `92c16533` | `gpaper-onyx` |
| MIP11 | Wacom Movink Pad 11 | `5HL21V5007384` | `gpaper-core` |

---

## Notesprout (main app) Tiers

Tiers mirror README.md — change them in both places or they drift.

- **Tier 1 (primary, always-tested):** Supernote Manta (**flagship**) & Nomad (RattaNotebookView firmware ink — install **both** for any Ratta work), BOOX Go 10.3 Gen 2, Go 6 Gen II, Note Max, Palma2 Pro, NoteAir5C
- **Tier 2 (QA):** BOOX NoteAir4C, Tab XC, Go Color 7 Gen II, Wacom Movink Pad 11 & 14 Pro (GenericDrawingEngine)
- **Future:** Desktop/Web, Android phone, Android tablet
- **On hand, in no tier** — still installable on request, serials above: BOOX Go 10.3, Go 7, Paper 7
  (GenericDrawingEngine), Samsung Galaxy S26 Ultra. No serial is recorded for the
  Movink Pad 14 Pro.
