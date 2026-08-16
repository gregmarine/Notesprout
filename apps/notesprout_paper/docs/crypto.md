# Paper — Encryption & the launch spine (as built, Phase 1)

Encrypt-by-default, **global key only**. The key is a passphrase string; the Keystore protects only
the device-local caches. Files stay portable: stock SQLCipher 4 opens them with the passphrase
(verified: `sqlcipher` CLI on the Mac opened a pulled `notesprout.db` — `PRAGMA key='NSPT-…'`).

## Components (`crypto/`)

| Class | Role |
|---|---|
| `GlobalKey` | `mint()` — 160-bit, Crockford base32 (no I/L/O/U), `NSPT-` + 8 groups of 4; `format(entropy)` is the pure, tested part. `ensure(ctx)` returns the cached passphrase or mints + caches (synchronized). |
| `SecurePrefs` | One lock + one cached `EncryptedSharedPreferences` per file; one retry for the post-boot Keystore transient. |
| `PassphraseStore` | File `paper_secure`: `global_passphrase`, `recovery_key_acknowledged`. |
| `AttemptLimiter` | Same file: failure count + lockout timestamp per key (`GLOBAL`). Schedule (reference verbatim): 1–2 free · 3–4 → 30 s · 5–9 → 5 min · ≥ 10 → 1 h. Success resets. |
| `DerivedKeyStore` | File `paper_dkeys`: base64 raw key per file id. |
| `RawKeyDerivation` | PBKDF2-HMAC-SHA512 × 256,000, 32 bytes, salt = file bytes 0..15 — byte-exact to SQLCipher's KDF. `toHex` is `Locale.ROOT` (tested under `ar_EG`). `rawKeyLiteral` = `x'<hex>'`. |
| `KeyMaterial` | RAM → Keystore → derive+persist. `INDEX_FILE_ID = __notesprout_index__`; notebooks use their UUID. `invalidate(fileId)` on delete / stale; `clearAll` (debug forget). |
| `KeySession` | Process-RAM copy of the global passphrase, set once the index opens. Never persisted from here. |
| `SoilCrypto` | The only place SQLCipher factories/handles are made. `roomFactory(pass)` / `roomFactoryRawKey(raw)` (both wrapped in `NonDestructiveOpenHelperFactory`); `openRaw`/`openRawKey` + `verifyPassphrase`/`verifyRawKey` (**false for a missing/empty file** — a create-capable open would otherwise mint an empty DB); `requireExisting`; `createRaw` (the only raw create; refuses an existing non-empty file); `probe(file)` → `Invalid | Plaintext | Encrypted` by header only. |
| `KeyOpener` | `roomFactoryFor(ctx, fileId, file, pass)`: cached raw key **verified against the file** (stale → invalidate, fall back to passphrase), else passphrase + background `warm`. Requires the file to exist. |
| `data/NonDestructiveOpenHelperFactory` | `onCorruption` logs + throws, never deletes. Every Room open in Paper goes through it. |

## Open state machine — `PaperIndex.ensureReady` (called only by `BootstrapActivity`)

```
probe(notesprout.db)
  Invalid   → GlobalKey.ensure → create encrypted (Room, passphrase factory) → cache raw key → FIRST_LAUNCH
  Encrypted → cached passphrase? no → NEEDS_UNLOCK
              raw key (RAM/Keystore/derive) → verifyRawKey?
                 yes → open (raw-key factory) → READY
                 no  → invalidate; verifyPassphrase? yes → re-derive, open → READY · no → NEEDS_UNLOCK
  Plaintext → FOREIGN_FILE (never opened; error dialog)
```

`unlockAndOpen(ctx, typed)`: `verifyPassphrase` first (read-only, exists-guarded) → cache
passphrase → invalidate + re-derive raw key → open. Never opens Room with an unverified key.

## Screens (`bootstrap/`)

- `BootstrapActivity` — launcher, `noHistory`, paper-white; "Preparing…" appears only after 450 ms.
  READY/FIRST_LAUNCH → `RecoveryKeyActivity` unless acknowledged, else `LibraryActivity`;
  NEEDS_UNLOCK → `UnlockActivity`; failure → Retry / Close app dialog (never a crash loop).
- `RecoveryKeyActivity` — shows the key (monospace, selectable), Copy, checkbox "I've saved it"
  (click-guard + toast on Continue if unticked), persists the acknowledgement.
- `UnlockActivity` — key field + Unlock; tries as typed then `GlobalKey.normalize`d (upper-case + the
  Crockford confusables the alphabet omits: O→0, I/L→1 — so a reader who wrote "O" for "0" still
  unlocks; a correct key never contains I/L/O/U, so the fold can't corrupt one). This is v0's only
  recovery path, so it is deliberately forgiving. Wrong key → error text + `recordFailure`,
  file untouched (verified on MIP11: md5 identical before/after); lockout hides the entry row and
  shows a countdown; success → `recordSuccess`, acknowledged, library.
- `core/IndexGuard.ready(activity)` — first line of every index-touching `onCreate`; bounces to
  Bootstrap (`NEW_TASK|CLEAR_TASK`) when `PaperIndex.isReady()` is false.

## Debug-only tools (`src/debug/…/library/DebugMenu.kt`; no-op twin in `src/release`)

⋯ on the library top bar: **Show recovery key** (reveal + copy) · **Forget cached key** (clears
`PassphraseStore` + `KeyMaterial`, kills the process so the next launch really re-runs bootstrap
→ Unlock screen).

## Leak hygiene (standing)

Passphrases never logged, never in Intents, never in prefs other than SecurePrefs; covers only in the
encrypted index; browse/sort/recents prefs hold ids only. Delete invalidates the raw-key cache entry.

## Data-loss audit (Phase 6, walked 2026-08-15)

The v0 close-out audit — every claim below verified against the source, not assumed:

1. **Every open path wrapped.** All Room factories are built only in `SoilCrypto`
   (`roomFactory`/`roomFactoryRawKey`), each wrapped in `NonDestructiveOpenHelperFactory` whose
   `onCorruption` logs + throws (never deletes). `KeyOpener` and `PaperIndex`/`SoilDatabase` build
   through those factories; no factory is constructed elsewhere. The only raw (non-Room) opens are
   `openRaw`/`openRawKey`, used solely by the read-only `verify*` helpers.
2. **No create-capable open outside the two bootstrap entry points.** Creation is `SoilCrypto.createRaw`
   + `SoilDatabase.create` (new-notebook) and `PaperIndex.ensureReady`'s `Invalid` branch (new index);
   all three `require(!file.exists() || length==0)`. Every non-creation open first calls
   `SoilCrypto.requireExisting` (missing/empty → `SoilLockedException`), so a create-capable primitive
   pointed at a missing path can never fabricate a stub that masquerades as real data.
3. **Missing file never loops into unlock.** A missing/empty `.soil` → `NotebookSession.open` returns
   `Failed` → the activity toasts + `finish()`s. A missing index → `probe` = `Invalid` → *create*
   (first-launch), never the unlock path. `NEEDS_UNLOCK` is reached only for an existing `Encrypted`
   file whose cached key/passphrase no longer fits.
4. **Delete invalidates the key cache.** `deleteNotebookFiles` deletes the `.soil` + its
   `-wal/-shm/-journal` sidecars and calls `KeyMaterial.invalidate(id)` — which drops **both** the RAM
   map and the Keystore entry. (Fixed in Phase 6: this previously called `DerivedKeyStore.remove`
   directly, clearing only the Keystore and leaking the RAM entry for the process lifetime.)
5. **No passphrase in logs / intents / prefs.** No `putExtra`/`getStringExtra` carries a passphrase;
   the only log mentioning "passphrase" logs a `fileId`, never key material. The passphrase lives in
   `KeySession` (process RAM) and `PassphraseStore` (SecurePrefs / `EncryptedSharedPreferences`) only.
6. **No names in prefs.** `BrowseState`/`SortPrefs`/`RecentsPrefs` store ids and enum `.name`s
   (`NORMAL`/`PINNED`/`NAME`/`ASC`…) only — never a folder/notebook display name. Display names are
   resolved from the encrypted index at read time.
