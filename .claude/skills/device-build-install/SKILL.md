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
| Paper 7 (P7) | `T1737BBR0327` | | Samsung Galaxy S26 Ultra (S26U) | `R3GL307HGDH` |

- **Tier 1 (primary, always-tested):** BOOX Go 10.3 Gen 2 (**flagship**), Go 6 Gen II, Note Max, Palma2 Pro
- **Tier 2 (QA):** BOOX Go 10.3, Go 7, NoteAir5C/4C, Tab XC, Go Color 7 Gen II, Wacom Movink Pad 11 & 14 + Paper 7 (GenericDrawingEngine)
- **Future:** MacBook/Web, Supernote Nomad & Manta (GenericDrawingEngine)
