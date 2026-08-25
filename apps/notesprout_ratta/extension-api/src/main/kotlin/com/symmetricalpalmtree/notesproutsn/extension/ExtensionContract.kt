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
 * `IExtensionStore` (arc 11 / J2) is not a capability point but the **service** the host offers an
 * extension it has bound: a per-package encrypted key/value store the host owns, handed in as a
 * parameter and revoked when the bind ends. Its caps are the `STORE_*` constants below.
 */
object ExtensionContract {

    /** Current API version. An extension is used only if its `<service>` meta-data equals this. */
    const val API_VERSION: Int = 1

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

    /** `<meta-data>` name (on the `<service>`) carrying the extension's API version. */
    const val META_API_VERSION: String =
        "com.symmetricalpalmtree.notesproutsn.extension.API_VERSION"

    // ── Extension-store caps (`IExtensionStore`, arc 11 / J2 — enforced by the host) ──────
    // The store is host-owned and encrypted; an extension writes nothing to disk itself, ever.

    /** Longest key an extension may store (chars); the empty key is rejected. */
    const val STORE_MAX_KEY_CHARS: Int = 512

    /** Largest value an extension may store — 4 MiB, sized for one key per scratch page. A value
     *  above [STORE_MAX_INLINE_BYTES] travels only through `putLarge` / `getLarge` (a [LargeValue]
     *  over `SharedMemory`): a `byte[]` that size cannot cross a Binder. */
    const val STORE_MAX_VALUE_BYTES: Int = 4 * 1024 * 1024

    /** Largest value the `byte[]` `put` / `get` path carries (512 KiB — the Binder transaction
     *  budget). `put` above it → `IllegalArgumentException`; `get` of a *stored* value above it →
     *  `IllegalStateException` with the exact message [STORE_VALUE_LARGE]. */
    const val STORE_MAX_INLINE_BYTES: Int = 512 * 1024

    /** The exact `IllegalStateException` message `get` throws for a stored value above
     *  [STORE_MAX_INLINE_BYTES]. Extensions compare the message, not a substring. */
    const val STORE_VALUE_LARGE: String = "value is large — use getLarge"

    /** Most keys one extension's store may hold. */
    const val STORE_MAX_KEYS: Int = 50_000

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

    /** The exact `IllegalStateException` message the scratch-pad extension throws from `receiveInk`
     *  when the target page's encoded ink would exceed [STORE_MAX_VALUE_BYTES]. The host compares the
     *  message, not a substring. */
    const val SCRATCH_PAGE_FULL: String = "scratch page full"
}
