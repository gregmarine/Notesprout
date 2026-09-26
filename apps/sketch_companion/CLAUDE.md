# Sketch Companion — Claude Code Project Intelligence

A standalone phone companion to **NSE · Sketch** (`extensions/sketch`). Photograph a scene, frame it
at the Supernote page's 3:4, lay the sketch face's own grid over it, and sketch on the Supernote by
eye while looking at the phone. Not part of Notesprout; nothing here is shared at build time.

- **Package:** `com.symmetricalpalmtree.sketchcompanion` (debug `.dev`) · **minSdk 33** · one device,
  the Samsung Galaxy S26 Ultra `R3GL307HGDH`
- **Build / install:** see the `device-build-install` skill § Sketch Companion
- **Rules inherited from the root `CLAUDE.md`:** Kotlin, `kotlinx.serialization` only, no new Gradle
  dependencies without discussion, no Material Components, `Slog.d` not `Log.d`, never `runBlocking`
  on the UI thread, the paper design system (inkBlack on paperWhite, 1dp borders, 4dp radius, Tabler
  outline icons, no ripple, 44dp tap targets).

## The user's decisions (2026-09-25)

1. Camera = the system camera Intent (`TakePicture` + FileProvider). No CameraX, no `CAMERA`.
2. Library = the Android Photo Picker. No storage permission.
3. **Always crop to 3:4 portrait** — pan/zoom inside a fixed frame; the photo always covers it.
   Nomad 1404×1872 and Manta 1920×2560 are both exactly 3:4, so one frame and no device picker.
4. The grid mirrors the extension exactly: `grid/GridLayout.kt` is a verbatim copy of the sketch
   face's; Off / Lines / Dots; counts `2 4 6 8 12` / `16 20 24 28 32`; default Lines, 4.
5. Eight swatches (white, black, #888888, red, yellow, green, cyan, magenta), default white.
6. Three weights; Regular = the sketch page's 2 px line / 4 px dot at 1404 px wide.
7. Export = Save to Photos (`Pictures/Sketch Companion`, JPEG 95) **and** Share; the crop at source
   resolution capped at 4096 px wide. The photo is 100 % opaque, the grid on top.
8. The last session is restored: the photo is copied into `filesDir`, crop and grid settings in
   `filesDir/session.json`.
9. (2026-09-25, second round) **Rotate** with two fingers alongside pinch and pan — a turn within
   4° of a right angle snaps to it at the end of the gesture; the photo must still cover the frame,
   so a turn raises the least zoom. **Focus**: tap the photo (or the maximize button) for a
   full-screen view — black letterbox, every bar hidden, the screen kept on; tap or Back returns.
   **Lock**: the framing (zoom, pan, turn) is locked by default and persisted; the lock button on
   the top bar unlocks for an adjustment and re-locks.

## Shape

- `MainActivity` — the one screen. `ui/FrameView` — the 3:4 frame (matrix, clip, gestures, grid).
  `ui/LatchRow` — a row where exactly one button is down.
- `crop/CropMath` — the pure arithmetic of the frame (`zoom` + normalised centre + `angle`);
  `clamp` *is* the "always covers" invariant (the turned frame's bounding box inside the photo). `grid/GridPainter` is the one painter for screen and export.
- `photo/PhotoStore` + `photo/PhotoDecoder` — the held photo and its `ImageDecoder` display decode
  (orientation applied; ≤ 3072 px long edge). `export/ExportRenderer` decodes the region behind the
  turned frame straight from the file through `ImageDecoder.setCrop` (oriented space, no EXIF
  library) and draws it through the same turn the screen shows;
  `export/ExportSink` writes to MediaStore or a FileProvider cache file.
- JVM tests: `GridLayoutTest` (copied), `GridWeightTest`, `CropMathTest`.
