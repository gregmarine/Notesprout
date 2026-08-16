# Extensions — the extension model & contract v1

> Arc: `PAPER_EXTENSIONS_PLAN.md` (the cross-session memory for the extensions work). This doc is the
> subsystem reference. **Phase E0** established the contract library and the first extension APK; the
> host (`:app`) is wired to discover and call them in **E1**.

Notesprout's original design baked too many features into the core. Paper's core is **paper with
strokes** — a library of notebooks, each a stack of pages you write on. Everything else is added by
**extensions**: opt-in, removable, each a **separate APK** the core calls over Android IPC (AIDL).

The first extension is **Templates**: it offers the generated base templates (Lined / Dotted / Grid)
on the New-notebook screen and renders the chosen one into the WEBP the `.soil` already stores.
**Templates remain a core concept of the `.soil`** — a notebook shared with someone who lacks the
extension still shows its template, because the core reads and draws the stored WEBP exactly as before.
Only *how templates are offered and generated* lives in the extension.

There is **no Extensions UI yet**: extensions are installed and removed by hand (`adb install` /
`adb uninstall`, or Settings → Apps → Uninstall). An Extensions UI is a later arc.

---

## The extension model (concepts)

- **Extension** — an installed Android package. Its user-visible name is its application label
  (`ApplicationInfo.loadLabel`).
- **Extension point** — a named capability the core knows how to call. Each point has: an **intent
  action** (declared on a `<service>` intent-filter), an **AIDL interface**, and a **`<meta-data>` API
  version** on that `<service>`. One `<service>` per point per extension. **v1 has exactly one point:
  `TemplateProvider`.**
- **No launcher** — an extension declares **no launcher Activity**; it shows no icon in any app drawer.
  It is visible only under Settings → Apps (which is also how a user removes one until the Extensions UI
  exists).
- **Discovery** — the core runs
  `PackageManager.queryIntentServices(Intent(action), GET_META_DATA)`. The core manifest must declare
  the action in `<queries>` (mandatory on API 30+, or the query silently returns nothing). Disabled
  packages/components are not returned, so `pm disable` == uninstalled from the core's point of view.
- **Trust (v1: same-signature only)** — a discovered service is used only if the service is exported,
  its meta-data API version **equals** `ExtensionContract.API_VERSION`, and
  `PackageManager.checkSignatures(corePackage, extPackage) == SIGNATURE_MATCH`. The extension,
  symmetrically, refuses any caller whose uid is not the host package with a matching signature.
  Lifting to third parties later = one condition + the Extensions UI's consent step.
- **Calling** — the core binds (`bindService`, `BIND_AUTO_CREATE`), waits for `onServiceConnected` with
  a timeout, runs the AIDL call(s) on a background dispatcher under a timeout, and **unbinds in
  `finally`**. The core never holds a binding across screens; it binds per operation.
- **Payload rules** — the core hands an extension only what the call needs (for templates: page
  geometry + dpi). **Nothing about keys, files, the index, or notebook contents ever crosses.** Data
  coming back is untrusted: size-capped and bounded-decoded.

---

## Contract v1 (`:extension-api`)

`:extension-api` is an Android **library** (`namespace com.symmetricalpalmtree.notesprout.extension`,
`minSdk 29`, AIDL enabled) that **depends on nothing in `:app` and on no library beyond the Kotlin
stdlib**. Third parties will consume it as a published Maven artifact later; the module boundary keeps
that true. Dependency direction (Gradle-enforced, never violated): `:app → :extension-api` and
`:ext-templates → :extension-api`; `:app` and `:ext-templates` never depend on each other.

### `ExtensionContract`

| Constant | Value |
|---|---|
| `API_VERSION` | `1` |
| `ACTION_TEMPLATE_PROVIDER` | `"com.symmetricalpalmtree.notesprout.extension.TEMPLATE_PROVIDER"` |
| `META_API_VERSION` | `"com.symmetricalpalmtree.notesprout.extension.API_VERSION"` |
| `MIME_WEBP` | `"image/webp"` |
| `MAX_RENDER_BYTES` | `16 * 1024 * 1024` (16 MiB — hard cap the host enforces on a render result) |
| `templateIdentity(pkg, id)` | `"$pkg:$id"` |
| `parseIdentity(s)` | `Pair<pkg, id>?` — splits at the **first** `:`; null if `:` absent or either side empty. The Blank sentinel is the host's, not the contract's. |

### AIDL

`extension-api/src/main/aidl/com/symmetricalpalmtree/notesprout/extension/`:

```aidl
// TemplateInfo.aidl
package com.symmetricalpalmtree.notesprout.extension;
parcelable TemplateInfo;

// RenderedTemplate.aidl
package com.symmetricalpalmtree.notesprout.extension;
parcelable RenderedTemplate;

// ITemplateProvider.aidl
interface ITemplateProvider {
    /** Templates this provider offers, in display order. Ids are stable, ASCII, unique per provider. */
    List<TemplateInfo> listTemplates();
    /** Render [templateId] at exactly widthPx x heightPx for a panel of [dpi] as a lossless WEBP.
     *  Returns null if the id is unknown. Called on a Binder thread; may take seconds on e-ink CPUs. */
    RenderedTemplate render(String templateId, int widthPx, int heightPx, float dpi);
}
```

### Parcelables (hand-written — no `kotlin-parcelize`)

- `TemplateInfo(id: String, name: String)` — `writeString(id); writeString(name)`.
- `RenderedTemplate(memory: SharedMemory, byteCount: Int, mimeType: String)` —
  `writeParcelable(memory, flags); writeInt(byteCount); writeString(mimeType)`. The bytes are a complete
  WEBP file in `memory[0 until byteCount]`. Binder transactions are capped at ~1 MB, so a page bitmap
  can never travel as a plain `byte[]`; `SharedMemory` is ashmem-backed and Parcelable. **Handshake:**
  the **extension** creates the region (`SharedMemory.create(null, byteCount)`, maps RW, writes, unmaps,
  `setProtect(PROT_READ)`); the **host** maps read-only, copies out `byteCount` bytes, unmaps, closes.

Both parcelables carry `@JvmField val CREATOR` and match their `.aidl` declarations.

### Versioning rules (for the next point / next version)

- A **new extension point** = a new action string + a new AIDL interface + the same `META_API_VERSION`
  key on its own `<service>`.
- A **compatible** change (a method appended at the end of an interface, an optional field appended to a
  parcelable's write order) keeps `API_VERSION`; the host must tolerate old extensions (catch the
  `RemoteException` from an unimplemented transaction).
- An **incompatible** change bumps `API_VERSION`; the host then accepts a *range*
  (`MIN_API_VERSION..API_VERSION`) instead of exact equality.
- **Never** reorder or remove AIDL methods or parcel fields.

---

## The Templates extension (`:ext-templates`)

An Android **application** APK — `applicationId com.symmetricalpalmtree.notesprout.ext.templates`
(debug `.dev`), `versionName 0.1.0`, no NDK/native libs. `BuildConfig.HOST_PACKAGE` is
`com.symmetricalpalmtree.notesprout.dev` in debug and `com.symmetricalpalmtree.notesprout` in release
(the dev extension serves the dev core; release serves release). Dependencies: `:extension-api` +
`androidx.core:core-ktx`.

- **Label:** "Notesprout Paper · Templates" (debug: "Notesprout Paper · Templates Dev"). **Icon:** a
  puzzle piece with a green sprout inside it (`drawable/ic_launcher_foreground.xml`; adaptive icon,
  white background).
- **Manifest:** `android:allowBackup="false"`, **no Activity**, one exported `<service>`:
  ```xml
  <service android:name=".TemplateProviderService" android:exported="true">
      <intent-filter>
          <action android:name="com.symmetricalpalmtree.notesprout.extension.TEMPLATE_PROVIDER" />
      </intent-filter>
      <meta-data android:name="com.symmetricalpalmtree.notesprout.extension.API_VERSION" android:value="1" />
  </service>
  ```
- **`TemplateProviderService`** returns an `ITemplateProvider.Stub`. **Every** stub method first calls
  `CallerCheck.enforce(context)`: `Binder.getCallingUid()` → `getPackagesForUid(uid)` must contain
  `BuildConfig.HOST_PACKAGE` **and** `checkSignatures(uid, Process.myUid()) == SIGNATURE_MATCH`, else
  `SecurityException`. `render` renders into a `SharedMemory` per the handshake above. Binder threads
  call in — the stub holds no mutable state.
- **`TemplateRenderer`** — the v0 core `BuiltInTemplates` moved **verbatim** (same geometry: 8 mm
  spacing at device dpi, mdpi-authored 1 px rule / 2 px dot radius scaled by dpi, LINED top margin
  2×spacing, symmetric GRID origin at 1×spacing) + the WEBP encode (`WEBP_LOSSLESS`, quality 100).
  Templates: `lined` "Lined", `dotted` "Dotted", `grid` "Grid" (ids ASCII lower-case; names from
  `strings.xml`). **Blank is not a template** — it is the host's "no template" option.

---

## Build & install

```sh
cd ~/git/Notesprout/apps/notesprout_paper
./gradlew assembleDebug                       # all modules
./gradlew testDebugUnitTest                   # all modules
adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <serial> install -r ext-templates/build/outputs/apk/debug/ext-templates-debug.apk
adb -s <serial> shell pm enable com.symmetricalpalmtree.notesprout.ext.templates.dev          # BOOX sideload trap
adb -s <serial> shell pm disable-user --user 0 com.symmetricalpalmtree.notesprout.ext.templates.dev  # simulate "not installed"
adb -s <serial> uninstall com.symmetricalpalmtree.notesprout.ext.templates.dev
```

Both APKs are signed by the same debug keystore (`~/.android/debug.keystore`) — that is what satisfies
the same-signature trust rule in dev. An extension built on another machine will **not** be trusted by
this Mac's core build (different debug key) — expected, not a bug.
