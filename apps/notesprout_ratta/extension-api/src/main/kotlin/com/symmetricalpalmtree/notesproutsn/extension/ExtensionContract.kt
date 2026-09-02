package com.symmetricalpalmtree.notesproutsn.extension

/**
 * The stable contract shared by the Notesprout SN core (host) and a recognizer extension. This
 * library depends on nothing in `:app` and on no library beyond the Kotlin stdlib.
 *
 * SN's capability points are deliberately few: [ACTION_HANDWRITING_RECOGNIZER]
 * (`IHandwritingRecognizer`) exists solely so other HWR engines can slot in later — headings and the
 * markdown engine are core (the arc-3 amendment to the arc-1 "no extensions" rule) — and, on the
 * user's explicit arc-11 decision, the scratch pad. Action strings are SN-namespaced, so a Paper
 * extension on the same device never matches one, and each family's `HostCallerCheck` refuses the
 * other family's host.
 *
 * Since then: arc 15's exporters, arc 16's importers, arc 19's document editor, arc 21's tag
 * manager and arc 23's calendar — each on its own explicit user decision, seven points in all.
 *
 * `IExtensionStore` (arc 11 / J2, rebuilt arc 22 / X1) is not a capability point but the
 * **service** the host offers an extension it has bound: a per-package encrypted SQLite store the
 * host owns — the extension declares its tables ([StoreSchema]), sends parameterized SQL
 * ([StoreCodec] statements through a [StorePayload]) and reads rows back ([StoreResult]) — handed
 * in as a parameter and revoked when the bind ends. Its caps are the `STORE_*` constants below.
 */
object ExtensionContract {

    /**
     * Current API version. An extension is used only if its `<service>` meta-data is **between 1
     * and this**, inclusive — the declared number is the version the extension *requires of the
     * host*, not merely the one it was built against.
     *
     * The range rule is the arc-18 / D3 skew guard. Compatible-tail parcels protect the
     * new-host/old-extension direction for free (an absent tail keeps its old meaning), but not
     * the reverse: an old host that lists a new extension reads the tail as absent and acts on
     * the *old* meaning — for `NSE · PDF Export` that meant truncating the destination and
     * streaming a `.soil` at a pages-exporter, a guaranteed failure that then deletes the
     * document it overwrote. So an extension whose descriptor a host must understand *beyond*
     * the absent-tail default declares the version that introduced it (the PDF exporter declares
     * 2, for `ExporterInfo.sourceKind`; the text importer declares 3, for
     * `ImporterInfo.resultKind` — an old host reading that tail as absent would run text bytes
     * through the `.soil` probe, a guaranteed refusal after the work), and an older host skips it
     * at discovery instead of failing at the destination. Extensions the absent-tail default
     * serves correctly keep declaring 1 and run against every host.
     *
     * **4 since arc 21 / W1** — the TAG_MANAGER point. A tag extension's service declares 4 so an
     * older host, which knows nothing of `ITagManager`, never lists it; every other extension's
     * declaration is untouched (meta-data is per service).
     *
     * **5 since arc 21 / W4** — the tag target became a *pair* (a notebook, plus a page when the
     * target is one), which reshaped [TagShowing]'s wire form and the index's stored records. This is the
     * one bump so far that is **not** a compatible tail: a W1-shaped tag extension against a W4 host
     * would unmarshal a `TagShowing` wrongly. It fails loudly rather than quietly — the constructor
     * `require`s reject the result and the exception crosses as `IllegalArgumentException` — but the
     * declaration is what keeps it from being reached at all. Only the tag service moves; every
     * other extension keeps the version it declared.
     *
     * **6 since arc 22 / X1** — `IExtensionStore` was **replaced**: the key/value methods are gone
     * and the table-store methods stand in their transaction codes. The second break that is not a
     * compatible tail, and the first that breaks the *other* direction too: a version-5 scratch pad
     * calling transaction code 1 on a version-6 host would land on a different method with a
     * mismatched parcel, which is not reliably loud. So this bump also carries a **floor**
     * ([MIN_API_VERSION_FOR_STORE]): a service on a store-taking point is listed only in
     * `MIN_API_VERSION_FOR_STORE..API_VERSION`. Every store-taking service (scratch pad, document
     * editor, tag manager) redeclares 6; the stateless points (recognizer, exporters, importers)
     * keep their declarations and their floor of 1. `ITagManager` was reshaped in the same bump
     * (arc 22 / X3): `snapshot` — one ashmem blob of the whole index — is replaced by the paged
     * [TAGS_PAGE] / [ASSIGNMENTS_PAGE] reads the search merge runs.
     *
     * **7 since arc 23 / Y1** — the CALENDAR point ([ACTION_CALENDAR], SN's SEVENTH capability point,
     * granted by the user 2026-09-01). A compatible *addition*: no existing interface changes shape,
     * so every existing extension keeps declaring what it declares today and no door vanishes. The
     * floor became **per action** with it ([minApiVersion]): a point born at 7 has no older shape to
     * accept, so a calendar service is listed only at [MIN_API_VERSION_FOR_CALENDAR]; the three
     * arc-22 store-taking points keep their floor of 6 and the stateless points their floor of 1.
     */
    const val API_VERSION: Int = 7

    /**
     * The floor for a service on a **store-taking** point (arc 22 / X1): the host accepts such a
     * service only when it declares `MIN_API_VERSION_FOR_STORE..API_VERSION`, because the store
     * it is lent is a version-6 interface and an older extension would speak the old one at it.
     * Points that take no store keep the floor of 1 — see [minApiVersion].
     */
    const val MIN_API_VERSION_FOR_STORE: Int = 6

    /**
     * The floor for the calendar point (arc 23 / Y1): the point was born at API version 7, so there
     * is no older calendar shape a host could accept — a service declaring less is not a calendar
     * this host knows.
     */
    const val MIN_API_VERSION_FOR_CALENDAR: Int = 7

    /**
     * The lowest API version the host accepts for a service on [action] — **per action** since arc
     * 23 / Y1: [MIN_API_VERSION_FOR_STORE] for the three arc-22 store-taking points
     * ([ACTION_SCRATCH_PAD], [DocumentContract.ACTION_DOCUMENT_EDITOR], [ACTION_TAG_MANAGER]),
     * [MIN_API_VERSION_FOR_CALENDAR] for [ACTION_CALENDAR], 1 for every other. The range rule at
     * [API_VERSION] applies above it. A point that is not in the map has the floor of 1 — a new
     * point that needs one adds its row here, and the test that pins the map fails until it does.
     */
    fun minApiVersion(action: String): Int = MIN_API_VERSIONS[action] ?: 1

    /** Whether a service declaring [apiVersion] on [action] is one this host may bind — the range
     *  rule and the floor in one place, pure so the registry's decision is JVM-tested. */
    fun accepts(action: String, apiVersion: Int): Boolean = apiVersion in minApiVersion(action)..API_VERSION

    /** Every point with a floor above 1, and its floor. */
    private val MIN_API_VERSIONS: Map<String, Int> by lazy {
        mapOf(
            ACTION_SCRATCH_PAD to MIN_API_VERSION_FOR_STORE,
            DocumentContract.ACTION_DOCUMENT_EDITOR to MIN_API_VERSION_FOR_STORE,
            ACTION_TAG_MANAGER to MIN_API_VERSION_FOR_STORE,
            ACTION_CALENDAR to MIN_API_VERSION_FOR_CALENDAR,
        )
    }

    /** Intent action a handwriting-recognizer `<service>` declares in its intent-filter. */
    const val ACTION_HANDWRITING_RECOGNIZER: String =
        "com.symmetricalpalmtree.notesproutsn.extension.HANDWRITING_RECOGNIZER"

    /** Intent action a scratch-pad `<service>` declares in its intent-filter (arc 11 / J3). */
    const val ACTION_SCRATCH_PAD: String =
        "com.symmetricalpalmtree.notesproutsn.extension.SCRATCH_PAD"

    /** Intent action the scratch-pad extension's exported screen `<activity>` declares; the host
     *  resolves it with `setPackage(<the discovered service's package>)` and launches it **for a
     *  result** (a plain `startActivity` leaves `callingPackage` null and the screen refuses it). */
    const val ACTION_SCRATCH_PAD_SCREEN: String =
        "com.symmetricalpalmtree.notesproutsn.extension.SCRATCH_PAD_SCREEN"

    /** Intent action a tag-manager `<service>` declares in its intent-filter (arc 21 / W1 — SN's
     *  SIXTH capability point, granted by the user 2026-08-31). */
    const val ACTION_TAG_MANAGER: String =
        "com.symmetricalpalmtree.notesproutsn.extension.TAG_MANAGER"

    /** Intent action the tag extension's exported screen `<activity>` declares — the third
     *  screen-owning (tier-2) point, and the first whose screen carries **no paper**. Resolved with
     *  `setPackage(<the discovered service's package>)` and launched for a result; a plain
     *  `startActivity` leaves `callingPackage` null and the screen refuses it. */
    const val ACTION_TAG_MANAGER_SCREEN: String =
        "com.symmetricalpalmtree.notesproutsn.extension.TAG_MANAGER_SCREEN"

    /** Intent action a calendar `<service>` declares in its intent-filter (arc 23 / Y1 — SN's
     *  SEVENTH capability point, granted by the user 2026-09-01; no EIGHTH without another). The
     *  fourth screen-owning point and the second with paper, shaped exactly like the scratch pad:
     *  a held bind for the showing, the store lent at `begin`, both transfers through the bind. */
    const val ACTION_CALENDAR: String =
        "com.symmetricalpalmtree.notesproutsn.extension.CALENDAR"

    /** Intent action the calendar extension's exported screen `<activity>` declares. Resolved with
     *  `setPackage(<the discovered service's package>)` and launched for a result; a plain
     *  `startActivity` leaves `callingPackage` null and the screen refuses it. */
    const val ACTION_CALENDAR_SCREEN: String =
        "com.symmetricalpalmtree.notesproutsn.extension.CALENDAR_SCREEN"

    /** `<meta-data>` name (on the `<service>`) carrying the extension's API version. */
    const val META_API_VERSION: String =
        "com.symmetricalpalmtree.notesproutsn.extension.API_VERSION"

    // ── Extension-store caps (`IExtensionStore` v6, arc 22 / X1 — enforced by the host) ──────
    // The store is host-owned and encrypted; an extension writes nothing to disk itself, ever. The
    // extension declares its tables once (`StoreSchema`), then sends parameterized SQL and reads
    // encoded rows back. Every byte that crosses is validated by the host (`StoreSql`, `StoreCodec`).

    /** Largest payload that rides inline as a `byte[]` in a [StorePayload] (512 KiB — the Binder
     *  transaction budget). Above it the payload travels as a [LargeValue] over ashmem. */
    const val STORE_MAX_INLINE_BYTES: Int = 512 * 1024

    /** Largest single payload in either direction — 4 MiB: one statement batch, or one chunk of a
     *  query result. [LargeValue.requireValid] enforces it on the wire. */
    const val STORE_MAX_VALUE_BYTES: Int = 4 * 1024 * 1024

    /** Largest **whole** materialized query result (32 MiB): the host runs a query to completion,
     *  encodes the rows and hands them over in [STORE_MAX_VALUE_BYTES] chunks; past this it refuses
     *  with [STORE_RESULT_LARGE] and the extension pages with `LIMIT`. */
    const val STORE_MAX_RESULT_BYTES: Int = 32 * 1024 * 1024

    /** A row is never split across chunks, so one encoded row must fit one chunk. Above it the
     *  query is refused with [STORE_ROW_LARGE]. */
    const val STORE_MAX_ROW_BYTES: Int = STORE_MAX_VALUE_BYTES

    /** Most statements in one `exec` batch (one transaction). */
    const val STORE_MAX_BATCH_STATEMENTS: Int = 10_000

    /** Longest SQL text of one statement (chars). */
    const val STORE_MAX_SQL_CHARS: Int = 8_192

    /** Most bound arguments per statement — SQLite's default bind limit. */
    const val STORE_MAX_ARGS: Int = 999

    /** Most tables one extension's schema may create (counted over every step). */
    const val STORE_MAX_TABLES: Int = 64

    /** Most schema versions (steps) a [StoreSchema] may declare, and most statements per step. */
    const val STORE_MAX_SCHEMA_STEPS: Int = 256
    const val STORE_MAX_STEP_STATEMENTS: Int = 64

    /** Most unfinished query results one binder may hold open at a time (a result that needs more
     *  than one chunk is parked behind a handle until `next` drains it or `close` drops it). */
    const val STORE_MAX_OPEN_RESULTS: Int = 4

    // Typed refusals — `IllegalStateException` messages compared VERBATIM by the extension.

    /** The materialized result would exceed [STORE_MAX_RESULT_BYTES]; nothing was handed over. */
    const val STORE_RESULT_LARGE: String = "store result large"

    /** One encoded row would exceed [STORE_MAX_ROW_BYTES]; nothing was handed over. */
    const val STORE_ROW_LARGE: String = "store row large"

    /** `applySchema` with a version below the one already applied to this store — an extension
     *  never sees a store at a schema newer than it knows, and the host never rolls one back. */
    const val STORE_SCHEMA_NEWER: String = "store schema newer"

    /** `exec` / `query` on a binder that has not had `applySchema` called on it yet. */
    const val STORE_SCHEMA_UNAPPLIED: String = "store schema unapplied"

    /** A query needed a handle and this binder already holds [STORE_MAX_OPEN_RESULTS]. */
    const val STORE_RESULTS_OPEN: String = "store results open"

    // ── Handwriting-recognizer caps ──────
    // Enforced by the host BEFORE the call (no bind over the cap) and re-checked by the extension.

    /** Most strokes in one `recognizeInk` / `recognizePage` call. */
    const val MAX_INK_STROKES: Int = 2_000

    /** Most points (summed over all strokes) in one recognize call (≈ 480 KB of floats). */
    const val MAX_INK_POINTS: Int = 60_000

    /** The host truncates `preContext` to its last this-many chars before the call. */
    const val MAX_PRECONTEXT_CHARS: Int = 20

    /** Host-side cap on the text a recognize call returns (chars); the rest is dropped. */
    const val MAX_RECOGNIZED_CHARS: Int = 20_000

    /**
     * The exact message of the `IllegalStateException` a recognizer throws from `recognize*` when it
     * could not become READY within the call (still acquiring its model, or nothing acquired yet).
     * The host types that one case ("still downloading"); any other `IllegalStateException` is an
     * engine failure. Recognizers must use this constant — the host compares the message, not a
     * substring.
     */
    const val RECOGNIZER_NOT_READY: String = "recognizer not ready"

    // ── Scratch pad (`IScratchPad`, arc 11 / J3) ──────
    // The screen's launch extras / result code, the ink-transfer caps (host-enforced outward before
    // any bind, re-checked inward on both sides) and the per-Binder-call chunk sizes. The values are
    // Paper's **shipped** ones (its arc-6 S2 outcome), not its plan appendix's pre-S2 table.

    /** Boolean launch extra — true when the pad is opened from a notebook (the pad shows its Send buttons). */
    const val EXTRA_SCRATCH_SEND_ENABLED: String = "sendEnabled"

    /** Boolean launch extra — true right after a `receiveInk` (the pad opens on the received page, strokes selected). */
    const val EXTRA_SCRATCH_OPEN_RECEIVED: String = "openReceived"

    /** Activity result code: the pad has outbound ink for `takeOutgoing` (= `Activity.RESULT_FIRST_USER`). */
    const val RESULT_SCRATCH_SEND: Int = 1

    /** `receiveInk` placement: a new page after the pad's current page / the current page itself. */
    const val PLACEMENT_NEW_PAGE: Int = 0
    const val PLACEMENT_CURRENT_PAGE: Int = 1

    /** Most strokes / points (summed) in one transfer, either direction. */
    const val MAX_TRANSFER_STROKES: Int = 10_000
    const val MAX_TRANSFER_POINTS: Int = 400_000

    /** Most strokes / points per Binder call (≈ 320 KB of floats — under the ~1 MB transaction budget
     *  with headroom); the host chunks, the extension re-checks ([InkBundle.requireValid]). */
    const val TRANSFER_CHUNK_STROKES: Int = 300
    const val TRANSFER_CHUNK_POINTS: Int = 20_000

    /**
     * Most chunks the host drains on `takeOutgoing` — a **safe upper bound on what [InkChunks.chunk]
     * can produce** for any transfer inside [MAX_TRANSFER_STROKES] / [MAX_TRANSFER_POINTS], because
     * a drain that stops early reports a legal transfer as truncated.
     *
     * A chunk closes for one of two reasons, and both have to be counted (arc 11 / J6 — the
     * stroke-only derivation this constant used to carry was 34, and a transfer of 39 strokes of
     * 10 001 points is inside both caps yet chunks into 39):
     *
     * - **stroke-driven** — the chunk held [TRANSFER_CHUNK_STROKES]: at most
     *   `MAX_TRANSFER_STROKES / TRANSFER_CHUNK_STROKES` of those;
     * - **point-driven** — the chunk's points plus the *next* chunk's first stroke crossed
     *   [TRANSFER_CHUNK_POINTS]. Summed over those chunks each point is counted at most twice, so
     *   there are fewer than `2 * MAX_TRANSFER_POINTS / TRANSFER_CHUNK_POINTS` of them;
     * - plus the last chunk, which closes because the strokes ran out.
     *
     * The bound is loose on purpose — it is a runaway guard, not a target. The drain normally stops
     * at the first empty bundle, one call after the ink.
     */
    const val TRANSFER_MAX_CHUNKS: Int =
        MAX_TRANSFER_STROKES / TRANSFER_CHUNK_STROKES +
            2 * MAX_TRANSFER_POINTS / TRANSFER_CHUNK_POINTS + 1

    // ── Calendar (`ICalendar`, arc 23 / Y1) ──────
    // The pad's three, mirrored: the screen's launch extras and its result code. The transfer caps
    // and the chunking above are reused unchanged — `MAX_TRANSFER_*`, `TRANSFER_CHUNK_*`,
    // `TRANSFER_MAX_CHUNKS`, `InkChunks` — a calendar page's ink is ink like any other. Where the pad
    // names a placement by an int, the calendar names its target page with a `CalendarTarget`.

    /** Boolean launch extra — true when the calendar is opened from a notebook (it shows its Send buttons). */
    const val EXTRA_CALENDAR_SEND_ENABLED: String = "calendarSendEnabled"

    /** Boolean launch extra — true right after a `receiveInk` (the calendar opens on the target page, strokes selected). */
    const val EXTRA_CALENDAR_OPEN_RECEIVED: String = "calendarOpenReceived"

    /** Activity result code: the calendar has outbound ink for `takeOutgoing` (= `Activity.RESULT_FIRST_USER`). */
    const val RESULT_CALENDAR_SEND: Int = 1

    /**
     * Boolean Intent extra on the calendar screen (arc 23 / Y4, the user's call): a trusted scratch
     * pad is installed, so the calendar shows its own Scratch Pad door. Discovery is the host's —
     * an extension never queries for another — and this is the third boolean the Intent carries;
     * still no content, no id, no path.
     */
    const val EXTRA_CALENDAR_SCRATCH_PAD_AVAILABLE: String = "calendarScratchPadAvailable"

    /**
     * The calendar screen's result when its Scratch Pad door was tapped (arc 23 / Y4): the host
     * opens the pad and brings the calendar back — at its bookmark — when the pad is closed without
     * sending. An extension screen refuses any caller but the host, so a door from one extension to
     * another is always the host's to walk through. A compatible addition: the calendar keeps
     * declaring 7.
     */
    const val RESULT_CALENDAR_OPEN_SCRATCH_PAD: Int = 2

    // ── Tags (`ITagManager`, arc 21 / W1, on rows since arc 22 / X3) ──────
    // The three caps below are the arc-21 wizard's, and since X3 they are **policy and nothing
    // else**: the tag index is `tag` / `assignment` rows in the extension's store, so a cap is a
    // `COUNT(*)` check inside the insert that would break it — race-free, because the count and the
    // insert are one statement in one transaction. Arc 21's size arithmetic (`WORST_CASE_BYTES`,
    // `MAX_TAG_ID_CHARS`, `CompactId`) is deleted with the one-store-value layout that needed it;
    // there is no longer any relationship between a cap here and a byte budget anywhere.

    /** Longest a tag may be, measured on its **normalized display form** ([TagRules.display]).
     *  Multi-word tags are the point, so the only restriction is the length. */
    const val MAX_TAG_CHARS: Int = 64

    /** Most distinct tags one library may hold. Past it, creating a new tag is refused. */
    const val MAX_TAGS: Int = 5_000

    /** Most tag→target assignments in the whole index. */
    const val MAX_TAG_ASSIGNMENTS: Int = 50_000

    // A target id has no length cap of its own since W4: it is a canonical UUID or it is not a
    // target ([TagRules.isId] is the check, at every door and in both records).

    /**
     * Records one `ITagManager.tags` reply carries (arc 22 / X3).
     *
     * It exists because a **Binder transaction has a budget** (≈ 1 MiB, shared by everything in
     * flight on the process) and a reply is a plain parcel — no ashmem on this call. [MAX_TAGS]
     * records at roughly 250 parcel bytes apiece is over a megabyte, which would not merely be slow:
     * it would be a `TransactionTooLargeException`. So the listing is paged, and a page shorter than
     * this ends the loop ([TagPages]).
     */
    const val TAGS_PAGE: Int = 500

    /**
     * Rows one `ITagManager.assignmentsOf` reply carries (arc 22 / X3). Bigger than [TAGS_PAGE]
     * because an assignment is three ids and no user text — about 120 parcel bytes — so a thousand
     * of them is still a small fraction of the transaction budget.
     */
    const val ASSIGNMENTS_PAGE: Int = 1_000

    /**
     * Most tag ids one `ITagManager.assignmentsOf` call may name (arc 22 / X3); the host chunks a
     * longer selection and the extension refuses one that is longer.
     *
     * The extension turns the list into one `IN (?, ?, …)`, so the bound is really SQLite's
     * per-statement bind limit ([STORE_MAX_ARGS] 999) with room left for the `LIMIT`/`OFFSET` binds
     * and for the statement to stay well inside [STORE_MAX_SQL_CHARS].
     */
    const val ASSIGNMENT_QUERY_TAGS: Int = 500

    /** Longest a target's display label may be (the screen's title — display only, never a path). */
    const val MAX_TARGET_LABEL_CHARS: Int = 200

    /** The exact `IllegalStateException` message a tag manager throws when a cap ([MAX_TAGS] /
     *  [MAX_TAG_ASSIGNMENTS]) refuses a new tag or assignment — nothing was written. The host
     *  compares the message, not a substring. */
    const val TAG_INDEX_FULL: String = "tag index full"
}
