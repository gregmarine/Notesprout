# NoteSprout Android — Claude Code Context

Native Android app for NoteSprout. Kotlin. Primary active codebase.
See repo root `CLAUDE.md` for full project context.

---

## Build Commands

**Always use Java 17. Always.**

```bash
# Debug build
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home ./gradlew assembleDebug

# Release build
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home ./gradlew assembleRelease

# Install debug APK
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Install release APK
adb install -r app/build/outputs/apk/release/app-release-unsigned.apk

# Build + install in one shot (debug)
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home ./gradlew assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk

# Target a specific device by serial
adb -s <DEVICE_SERIAL> install -r app/build/outputs/apk/debug/app-debug.apk
```

> ⚠️ Never run `./gradlew` without the `JAVA_HOME` prefix. It will pick up the wrong Java version and fail.

---

## Drawing Engine Architecture

Device detection and drawing are fully abstracted. `CanvasActivity` has zero knowledge of which engine is running.

```
CanvasActivity
  └── DrawingEngineFactory
        └── DeviceDetector
              ├── BOOX → OnyxDrawingEngine
              └── GENERIC_ANDROID → GenericDrawingEngine
```

**DeviceDetector** — Three-layer BOOX detection:
1. `Build.MANUFACTURER` / `Build.BRAND` / `Build.MODEL` string check
2. `Class.forName("com.onyx.android.sdk.pen.TouchHelper")` existence check
3. Returns `BOOX` or `GENERIC_ANDROID`

**OnyxDrawingEngine** — Wraps Onyx `TouchHelper` and `EpdController`.
- All Onyx SDK calls must be wrapped in `try/catch`
- Never let SDK exceptions propagate to `CanvasActivity`
- `isAvailable` set to `false` if `initialize()` throws

**GenericDrawingEngine** — Standard Android `Canvas` / `MotionEvent`.
- Works on Wacom Movink, generic tablets, phones
- Handles `TOOL_TYPE_STYLUS` and `TOOL_TYPE_FINGER`

---

## Key Files

```
app/src/main/kotlin/com/notesprout/notesprout/
  device/
    DeviceDetector.kt         # BOOX vs GENERIC_ANDROID detection
    DrawingEngineFactory.kt   # Routes DeviceDetector result to the correct engine
    DrawingEngine.kt          # Interface + DrawingEngineCallback
    OnyxDrawingEngine.kt      # BOOX / Onyx SDK implementation
    GenericDrawingEngine.kt   # Standard Android fallback
  data/
    Models.kt                 # NotebookMeta, PageModel, LayerModel, StrokeModel
    NotebookRegistry.kt       # Disk-level notebook discovery
    SoilDatabase.kt           # SQLite .soil file access
  undo/
    UndoAction.kt             # Undo action types
    UndoStack.kt              # Undo/redo stack
  ui/
    EinkStyle.kt              # Reusable e-ink UI helpers
    TemplatePickerDialog.kt   # Template selection dialog
  CanvasActivity.kt           # Drawing UI — engine-agnostic
  NotebookListActivity.kt     # Notebook list
```

---

## Coding Rules

- All Onyx SDK imports must be wrapped so files compile even without the SDK present
- `CanvasActivity` must remain engine-agnostic — no direct Onyx SDK references
- Always use `try/catch` around Onyx SDK calls — never let them throw to the caller
- Always light mode — no dark mode support
- E-ink optimized UI throughout — flat, minimal, no gradients or shadows
- Soft deletes on all objects — use `deletedAt`, never hard delete
- Stable UUIDs for all pages, layers, and objects
