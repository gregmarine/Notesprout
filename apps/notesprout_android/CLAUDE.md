# NoteSprout Android — Germination

## App Identity
- Package: com.notesprout.android
- Language: Kotlin
- Min SDK: 26 / Target SDK: 35
- Build: Gradle Kotlin DSL

## Runtime Constraints
These were discovered during device testing and must never be regressed.

### PRAGMA calls
Always use `rawQuery().close()` for PRAGMA statements.
Never `execSQL()` for PRAGMAs that return result rows.
The BOOX SQLite driver rejects `execSQL()` for these.

### QuickJS plugin registration
Plugins register under `__plugins__[PLUGIN_ID]`.
Kotlin initializes `var __plugins__ = {}` before any plugin JS is evaluated.
Never use `globalThis[PLUGIN_ID]` — unavailable on this QuickJS build.

### QuickJS thread stack
ALL `callFunction()` calls must run on PluginEngine's dedicated `Executors.newSingleThreadExecutor` thread created with a 4MB stack size.
`Dispatchers.IO` threads have ~512KB stacks — always fatal for QuickJS function invocation on device.
Never call QuickJS functions from `Dispatchers.IO` directly. Always go through PluginEngine.

## Architecture
- Single objects table in SQLite — everything is a row
- BaseObject: id, parentId, pluginId, boundingBox, order, createdAt, updatedAt, deletedAt, syncVersion, data
- Plugin system: JS plugins via QuickJS runtime
- Plugin types: Structural (Notebook, Page, Layer), Tools (Gel Pen, Eraser, etc.)
- Plugin location: src/main/assets/plugins/{type}/{name}/index.js
- Kotlin coroutines for all async work

## Package Structure
- core/     — app lifecycle, base classes
- data/     — database layer
- plugins/  — plugin system and QuickJS runtime
- canvas/   — drawing and rendering
- ui/       — activities and views
- sync/     — future sync

## Host API Namespaces (plugin surface)
- context   — tool state, plugin metadata, current object
- canvas    — draw, refresh, clear
- data      — save, load, softDelete, query
- events    — lifecycle hooks
- external  — file export, clipboard, network, external.ai

## E-ink First
- True black and white only
- No animations or transitions
- No shadows or gradients
- Force light mode always
- High contrast everything

## Build
```
cd apps/notesprout_android
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home ./gradlew assembleDebug
```

## ADB Install
```
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
