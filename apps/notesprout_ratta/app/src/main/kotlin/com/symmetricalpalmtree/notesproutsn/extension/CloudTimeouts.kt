package com.symmetricalpalmtree.notesproutsn.extension

/**
 * The host's call budgets for the cloud point (arc 25 / V1) — one row per [ICloudStorage] method.
 *
 * **A Binder call cannot be cancelled.** When a budget here runs out the host resumes with an
 * [ExtensionCallException] while the transaction it started keeps running on its own thread inside
 * the provider's process: the upload finishes, or the folder gets created, *after* the host has
 * already told the person that nothing happened. **A timeout undoes nothing.** So a number here is
 * sized by the work actually measured on the device (the W6 rule — a budget is sized by the work and
 * never by taste), and a generous one is safer than a tight one, because the only thing a tight
 * budget buys is a lie on the glass.
 *
 * Every row below is a **placeholder**, marked `UNMEASURED`. V2 replaces each with the number the
 * Nomad reports for the measurement named beside it (`DRIVE_PLAN.md` § V2 — "Measure on device:
 * `status`, `list`, `upload` 1 MiB / 20 MiB, `download`"), and records the measurement next to the
 * constant the way [TagClient.SEARCH_TIMEOUT_MS] records its own reasoning.
 *
 * Note what is **not** in any of these numbers: the extension store's first open. A cold SQLCipher
 * KDF is seconds on the Nomad, and the pre-open rule puts it on IO *before* the bind — so no budget
 * here has to cover it.
 */
object CloudTimeouts {

    /** How long the host waits for the provider's service to connect. The seam's own constant —
     *  binding is the same work for every point, so this is not a cloud number to measure. */
    const val BIND_MS: Long = ExtensionBinder.BIND_TIMEOUT_MS

    /**
     * `status` — **UNMEASURED** (placeholder 4 000 ms).
     *
     * The lightest call on the seam: it never touches the network (the contract forbids it) and the
     * store is already open by the time the bind happens, so what is left is one indexed read of a
     * few short rows and the parcel back. Four seconds is a deliberately loose ceiling for that.
     *
     * **V2 measures:** `status` on the Nomad, cold (first call after a boot) and warm.
     */
    const val STATUS_MS: Long = 4_000L

    /**
     * `disconnect` — **UNMEASURED** (placeholder 15 000 ms).
     *
     * One best-effort revoke round trip to the provider plus the store write that forgets the token.
     * The revoke is a network call and the provider bounds it itself; this is the outer bound on a
     * slow link. Disconnect is idempotent, so a timeout here is recoverable by tapping again.
     *
     * **V2 measures:** a real Disconnect on the Nomad, on wifi.
     */
    const val DISCONNECT_MS: Long = 15_000L

    /**
     * `list` — **UNMEASURED** (placeholder 20 000 ms).
     *
     * One folder listing: a token refresh if the access token has expired, then the provider's own
     * paging up to [CloudContract.MAX_LIST_ENTRIES] entries. The host's own folders hold a handful of
     * files, so the common case is one request; the budget covers the refresh in front of it.
     *
     * **V2 measures:** `list` of `Exports/` and of a folder with ~50 files, on the Nomad.
     */
    const val LIST_MS: Long = 20_000L

    /**
     * `ensureFolder` — **UNMEASURED** (placeholder 30 000 ms).
     *
     * Up to [CloudContract.MAX_PATH_DEPTH] find-or-create round trips in sequence — two requests per
     * segment in the worst case (a find that misses, then a create). It is the only method whose cost
     * scales with the path, which is why it is not simply [LIST_MS].
     *
     * **V2 measures:** `ensureFolder` for `Exports/<new folder>` (2 segments, both created) on the
     * Nomad, then multiplied out to the depth cap.
     */
    const val ENSURE_FOLDER_MS: Long = 30_000L

    /**
     * `upload` of a **small** file, ≤ 5 MiB — **UNMEASURED** (placeholder 60 000 ms).
     *
     * The multipart path: the folder resolved, the name looked up (replace-by-name is find-then-update,
     * never a blind create), then one request carrying the whole body. A page-bundle PDF or a small
     * `.soil` lands here.
     *
     * **V2 measures:** `upload` of 1 MiB on the Nomad, on wifi; the placeholder is that measurement
     * scaled to the 5 MiB ceiling with room for the two lookups in front of it.
     */
    const val UPLOAD_SMALL_MS: Long = 60_000L

    /**
     * `upload` of a **large** file — **UNMEASURED** (placeholder 180 000 ms **per 20 MiB**).
     *
     * Above 5 MiB the provider uses a resumable session: one request to open it, then chunks. The
     * cost is linear in the byte count, so this row is a **rate**, not a flat budget — the caller
     * scales it by the file's size (a 100 MiB backup is five times this). It is the one budget on the
     * seam that a caller must compute rather than read.
     *
     * **V2 measures:** `upload` of 20 MiB on the Nomad, on wifi, and the number here becomes that
     * measurement with a wide margin for a slower link.
     */
    const val UPLOAD_LARGE_MS: Long = 180_000L

    /**
     * `download` — **UNMEASURED** (placeholder 120 000 ms).
     *
     * A metadata fetch for the entry, then the bytes streamed into the host's fd and fsynced. Import
     * reads whole `.soil` files here, so it is sized like a large upload rather than a small one;
     * V2 may well split it into a rate the same way [UPLOAD_LARGE_MS] is.
     *
     * **V2 measures:** `download` of a ~20 MiB `.soil` from `Backups/` on the Nomad.
     */
    const val DOWNLOAD_MS: Long = 120_000L

    /**
     * `delete` — **UNMEASURED** (placeholder 15 000 ms).
     *
     * One round trip; a folder delete is one call on Drive whatever it holds. Nothing the host does
     * this arc deletes on its own — it exists for a replace-by-name that must clear a same-named
     * folder, and for a future prune.
     *
     * **V2 measures:** `delete` of one file on the Nomad.
     */
    const val DELETE_MS: Long = 15_000L
}
