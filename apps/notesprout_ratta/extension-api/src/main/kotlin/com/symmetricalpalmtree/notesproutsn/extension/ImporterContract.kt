package com.symmetricalpalmtree.notesproutsn.extension

/**
 * The notebook-importer capability point (arc 16 / I1 — SN's FOURTH, on the user's explicit
 * 2026-08-28 decision). The exporter point's mirror: any number of trusted importer extensions may
 * register under [ACTION_NOTEBOOK_IMPORTER]; each `describe()`s the formats it accepts and the
 * host's library Import button lists whatever is installed.
 *
 * **The seam is the exporter's, reversed: the host keys, the extension delivers via fds.**
 * Everything that touches a key — the probe, the unlock, the re-key to this device's global key,
 * placement, remap, the Garden and index writes — runs in the host. The extension receives **two
 * `ParcelFileDescriptor`s** (read: the user's picked document · write: a host-owned cache file)
 * plus an [ImportSpec], and streams the bytes across. **No passphrase, no path, no SQLCipher ever
 * crosses**; the extension writes only through the granted write fd — the writes-nothing-to-disk
 * rule, kept to the letter.
 *
 * Caps are shared with [ExporterContract] by reference — one set of bounds for both directions of
 * the same seam.
 */
object ImporterContract {

    /** Intent action a notebook-importer `<service>` declares in its intent-filter. */
    const val ACTION_NOTEBOOK_IMPORTER: String =
        "com.symmetricalpalmtree.notesproutsn.extension.NOTEBOOK_IMPORTER"

    // ── Descriptor caps (enforced by the parcelable constructors — unmarshal is validation;
    //    a descriptor that fails them drops that importer with a log line, never a crash) ──────

    /** Most file extensions one importer may declare it accepts. */
    const val MAX_FILE_EXTENSIONS: Int = 8

    /** Most MIME types one importer may declare (they seed the OPEN_DOCUMENT filter). */
    const val MAX_MIME_TYPES: Int = 8

    // ── Timeouts (host-side, over `ExtensionBinder.call`) ──────

    /** `describe()` returns a small in-memory descriptor — fast. */
    const val DESCRIBE_TIMEOUT_MS: Long = ExporterContract.DESCRIBE_TIMEOUT_MS

    /**
     * `import()` streams the picked document through two fds — the export copy in the other
     * direction, so the arc-15 Nomad measurement transfers directly (100 MB flash copy ~0.45 s;
     * two minutes covers a 1 GB document even through a slow DocumentsProvider at 10 MB/s).
     */
    const val IMPORT_TIMEOUT_MS: Long = ExporterContract.EXPORT_TIMEOUT_MS
}
