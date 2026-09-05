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
 * Every row below carries its **Nomad measurement** (arc 25 / V2, 2026-09-04, the debug menu's
 * "Cloud probe" on home wifi, `DRIVE_PLAN.md` § V2 ledger) beside the budget, the way
 * [TagClient.SEARCH_TIMEOUT_MS] records its own reasoning. A budget is 5–30× its measurement: the
 * margin is for a slow link, never for taste.
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
     * `status` — **measured 772 ms cold / 51 ms warm**; budget 4 000 ms.
     *
     * The lightest call on the seam: it never touches the network (the contract forbids it) and the
     * store is already open by the time the bind happens, so what is left is one indexed read of a
     * few short rows and the parcel back. Four seconds is a deliberately loose ceiling for that.
     *
     * **Measured (Nomad):** 772 ms on the first call of a host process (that includes the
     *  host-side store open, which the pre-open puts before the bind), 51 ms warm. 5× the cold.
     */
    const val STATUS_MS: Long = 4_000L

    /**
     * `disconnect` — **measured ≈ 160 ms**; budget 15 000 ms.
     *
     * One best-effort revoke round trip to the provider plus the store write that forgets the token.
     * The revoke is a network call and the provider bounds it itself; this is the outer bound on a
     * slow link. Disconnect is idempotent, so a timeout here is recoverable by tapping again.
     *
     * **Measured (Nomad):** the revoke POST answered http 200 in 137 ms and the store forget
     *  followed within 20 ms. ~90× that — the margin is the outer bound on a slow link, and a
     *  timeout is recoverable by tapping again.
     */
    const val DISCONNECT_MS: Long = 15_000L

    /**
     * `list` — **measured 530 / 805 / 1 056 ms at depth 0 / 1 / 2**; budget 20 000 ms.
     *
     * One folder listing: a token refresh if the access token has expired, then the provider's own
     * paging up to [CloudContract.MAX_LIST_ENTRIES] entries. The host's own folders hold a handful of
     * files, so the common case is one request; the budget covers the refresh in front of it.
     *
     * **Measured (Nomad):** ≈ 270 ms per path hop (one `find` request each) plus one listing
     *  request; at the depth cap that is ≈ 2.5 s, so the budget is ~8× the deepest path. A folder
     *  with ~50 files was not measured — a single page carries 1 000, so it is one request more.
     */
    const val LIST_MS: Long = 20_000L

    /**
     * `ensureFolder` — **measured 3 981 ms** (2 segments, both created, root created too); budget 30 000 ms.
     *
     * Up to [CloudContract.MAX_PATH_DEPTH] find-or-create round trips in sequence — two requests per
     * segment in the worst case (a find that misses, then a create). It is the only method whose cost
     * scales with the path, which is why it is not simply [LIST_MS].
     *
     * **Measured (Nomad):** 3 981 ms for `Exports/probe` on a fresh account — the token refresh,
     *  the root's find + create, and two find + create pairs. At the depth cap (8 segments, all
     *  created) that extrapolates to ≈ 7 s; the budget is ~4× that.
     */
    const val ENSURE_FOLDER_MS: Long = 30_000L

    /**
     * `upload` of a **small** file, ≤ 5 MiB — **measured 2 901 ms for 1 MiB**; budget 60 000 ms.
     *
     * The multipart path: the folder resolved, the name looked up (replace-by-name is find-then-update,
     * never a blind create), then one request carrying the whole body. A page-bundle PDF or a small
     * `.soil` lands here.
     *
     * **Measured (Nomad):** 2 901 ms for 1 MiB — four lookups (≈ 1.1 s) and one multipart POST
     *  (1 787 ms). Scaled to the 5 MiB ceiling ≈ 10 s; the budget is 6× that.
     */
    const val UPLOAD_SMALL_MS: Long = 60_000L

    /**
     * `upload` of a **large** file — **measured 6 435 ms for 20 MiB**; budget 120 000 ms **per 20 MiB**.
     *
     * Above 5 MiB the provider uses a resumable session: one request to open it, then chunks. The
     * cost is linear in the byte count, so this row is a **rate**, not a flat budget — the caller
     * scales it by the file's size (a 100 MiB backup is five times this). It is the one budget on the
     * seam that a caller must compute rather than read.
     *
     * **Measured (Nomad):** 6 435 ms for 20 MiB — the lookups, the session POST (462 ms) and one
     *  PUT of the bytes at ≈ 4.3 MB/s (4 907 ms). The budget is ~19× that: a link twenty times
     *  slower than home wifi (≈ 200 KB/s) still lands 20 MiB inside it.
     */
    const val UPLOAD_LARGE_MS: Long = 120_000L

    /**
     * `download` — **measured 4 343 ms for 20 MiB**; budget 120 000 ms (flat).
     *
     * A metadata fetch for the entry, then the bytes streamed into the host's fd and fsynced. Import
     * reads whole `.soil` files here, so it is sized like a large upload rather than a small one;
     * V2 may well split it into a rate the same way [UPLOAD_LARGE_MS] is.
     *
     * **Measured (Nomad):** 4 343 ms for 20 MiB — one metadata GET (198 ms) then the stream at
     *  ≈ 5.4 MB/s (3 863 ms) and the fsync. Kept **flat** rather than a rate: the host knows the
     *  size from the listing, but V5's imports are single `.soil` files, and 120 s covers 100 MiB at
     *  a fifth of wifi speed. If a measured import ever needs more, make it a rate like upload.
     */
    const val DOWNLOAD_MS: Long = 120_000L

    /**
     * `delete` — **measured 729 ms**; budget 15 000 ms.
     *
     * One round trip; a folder delete is one call on Drive whatever it holds. Nothing the host does
     * this arc deletes on its own — it exists for a replace-by-name that must clear a same-named
     * folder, and for a future prune.
     *
     * **Measured (Nomad):** 729 ms for one file (one DELETE, http 204, 677 ms). 20× that.
     */
    const val DELETE_MS: Long = 15_000L

    /** The ceiling for [UPLOAD_SMALL_MS] — at or below this the provider sends one multipart
     *  request; above it, a resumable session (`ICloudStorage.upload`). 5 MiB, the provider's own
     *  boundary and the reason there are two upload rows rather than one. */
    const val UPLOAD_SMALL_LIMIT_BYTES: Long = 5L * 1024 * 1024

    /** The unit [UPLOAD_LARGE_MS] is a rate *per*: one 20 MiB slice of a resumable upload. */
    const val UPLOAD_LARGE_UNIT_BYTES: Long = 20L * 1024 * 1024

    /**
     * The budget for one `upload` of [bytes] — the one number on this seam a caller must **compute**
     * rather than read, because [UPLOAD_LARGE_MS] is a rate and not a flat ceiling.
     *
     * At or below [UPLOAD_SMALL_LIMIT_BYTES] it is [UPLOAD_SMALL_MS] flat (one multipart request).
     * Above it the resumable path's cost is linear in the byte count, so the answer is
     * [UPLOAD_LARGE_MS] multiplied by the number of [UPLOAD_LARGE_UNIT_BYTES] slices the file needs,
     * **rounded up** — a 21 MiB file is charged two slices, because the second one is a real request
     * whatever it carries. A byte count below zero cannot describe a file and is charged the small
     * budget rather than throwing: this function decides how long to wait, and a caller's bad number
     * is refused by the argument checks, not here.
     *
     * Pure, so the table is JVM-tested rather than reasoned about.
     */
    fun uploadBudgetMs(bytes: Long): Long {
        if (bytes <= UPLOAD_SMALL_LIMIT_BYTES) return UPLOAD_SMALL_MS
        val slices = (bytes + UPLOAD_LARGE_UNIT_BYTES - 1) / UPLOAD_LARGE_UNIT_BYTES
        return UPLOAD_LARGE_MS * slices
    }
}
