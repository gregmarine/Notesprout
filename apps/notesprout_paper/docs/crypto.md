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
- `UnlockActivity` — key field + Unlock; tries as typed then upper-cased (Crockford keys are
  upper-case; lower-case transcriptions are accepted); wrong key → error text + `recordFailure`,
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
