package com.symmetricalpalmtree.notesproutsn.extension

/**
 * The notebook-exporter capability point (arc 15 / E1 — SN's THIRD, on the user's explicit
 * 2026-08-27 decision). Any number of trusted exporter extensions may register under
 * [ACTION_NOTEBOOK_EXPORTER]; each `describe()`s the one format it offers and the host's Export
 * screen lists whatever is installed.
 *
 * **The seam: the host keys, the extension delivers via fds.** Everything that touches a key runs
 * in the host — the transient checkpoint, the keying transform, the SAF destination. The extension
 * receives **two `ParcelFileDescriptor`s** (read: the prepared artifact · write: the destination)
 * plus an [ExportSpec], and produces the output. **No passphrase, no path, no SQLCipher ever
 * crosses**; the extension writes only through the granted write fd — the writes-nothing-to-disk
 * rule, kept to the letter.
 *
 * Option values cross as a plain id → value map ([ExportSpec.values]). The reserved
 * [OPTION_KEYING] is recognized and **executed by the host**: its transform runs host-side, and a
 * typed passphrase never enters the spec — only the chosen value id does. The same rule holds for
 * any [KIND_PASSPHRASE] option: the host collects the secret with its own fields and the spec
 * carries **no entry at all** for it — a passphrase-kind option exists to *ask the host* for a
 * host-executed step, never to receive the secret.
 *
 * Two further ids are reserved by arc 18 / D2, both plain toggles the exporter declares and labels
 * itself: [OPTION_PAGE_TEMPLATE], whose work is the host's render, and [OPTION_PROTECT], whose work
 * is the extension's — the host only collects the secret and hands it over on
 * [ExportSpec.exportSecret].
 */
object ExporterContract {

    /** Intent action a notebook-exporter `<service>` declares in its intent-filter. */
    const val ACTION_NOTEBOOK_EXPORTER: String =
        "com.symmetricalpalmtree.notesproutsn.extension.NOTEBOOK_EXPORTER"

    // ── Descriptor caps (enforced by the parcelable constructors — unmarshal is validation;
    //    a descriptor that fails them drops that exporter with a log line, never a crash) ──────

    /** Most options one exporter may declare. */
    const val MAX_OPTIONS: Int = 8

    /** Most choices one single-choice option may declare. */
    const val MAX_CHOICES: Int = 8

    /** Longest option / choice id (chars). Ids are `[A-Za-z0-9_-]+` — they double as spec-map keys. */
    const val MAX_ID_CHARS: Int = 32

    /** Longest human label (format label, option label, choice label). */
    const val MAX_LABEL_CHARS: Int = 80

    /** Longest file extension (chars, lowercase alphanumeric, no leading dot). */
    const val MAX_FILE_EXTENSION_CHARS: Int = 12

    /** Longest MIME type. */
    const val MAX_MIME_CHARS: Int = 128

    /** Longest option value in the spec map (a choice id or a toggle "0"/"1" — never free text). */
    const val MAX_SPEC_VALUE_CHARS: Int = 64

    /** Longest notebook display name carried in the spec (display only — never a path). */
    const val MAX_NAME_CHARS: Int = 200

    // ── Option kinds ──────

    /** One of a bounded list of choices; the value that crosses is the chosen choice id. */
    const val KIND_SINGLE_CHOICE: Int = 0

    /** On/off; the value that crosses is `"1"` / `"0"`. */
    const val KIND_TOGGLE: Int = 1

    /** A secret the host collects and consumes itself. **No entry ever crosses in the spec.** */
    const val KIND_PASSPHRASE: Int = 2

    // ── Source kinds (arc 18 — `ExporterInfo`'s compatible tail) ──────
    // What an exporter asks the host to hand it through the read fd. Absent on an old-shape
    // descriptor, which means SOURCE_SOIL — the tail changed nothing for existing exporters.

    /** The prepared `.soil` artifact, streamed verbatim (arc 15's original, and the default). */
    const val SOURCE_SOIL: Int = 0

    /**
     * A host-rendered page bundle ([PageBundle]) — every page baked full-fidelity into encoded
     * images, for an exporter that could never receive the `.soil` itself (no key ever crosses).
     * The output is a *transform* of the source, so the host's verbatim byte-count check does not
     * apply; verification is per source kind (destination corroboration only).
     */
    const val SOURCE_PAGES: Int = 1

    /**
     * Longest export secret ([ExportSpec.exportSecret], chars) — the ONE deliberate secret that
     * ever crosses an extension seam: user-typed, export-scoped, opens no Notesprout data (a PDF
     * password, say). Never the global passphrase, never the device key, never [KIND_PASSPHRASE]
     * (whose never-crosses meaning is unchanged).
     */
    const val MAX_EXPORT_SECRET_CHARS: Int = 128

    // ── The reserved keying option ──────
    // Declared by an exporter like any single-choice option, but recognized by id and EXECUTED BY
    // THE HOST: the transform (`SoilCrypto`) runs host-side on the cache temp before the fds are
    // opened, and the "rekey" choice makes the host show its own passphrase + confirm fields.

    /** The reserved option id. */
    const val OPTION_KEYING: String = "keying"

    /** Keep encrypted under this device's key — a pure file copy. */
    const val KEYING_KEEP: String = "keep"

    /** Re-key to a passphrase of the file's own (typed + confirmed, host-owned fields). */
    const val KEYING_REKEY: String = "rekey"

    /** Remove encryption — plaintext output (the host shows the inline plain warning). */
    const val KEYING_PLAIN: String = "plain"

    // ── The reserved arc-18 option ids (D2) ──────
    // Both are ordinary [KIND_TOGGLE] options an exporter declares for itself — so the user reads
    // the exporter's own label — but each is recognized by id, because each one names something
    // the *host* has to do about it. Declaring neither is still a complete descriptor.

    /**
     * "Render the page's paper under the ink" — reserved for a [SOURCE_PAGES] exporter, and
     * **executed by the host**: `"1"` bakes each page's template under its content (the D1
     * behavior), `"0"` bakes the ink on a white ground. The value crosses in the spec map like any
     * toggle, so the extension still learns what was asked, but the work is entirely the render's:
     * there is no template left in the bundle for an extension to put back.
     */
    const val OPTION_PAGE_TEMPLATE: String = "template"

    /**
     * "Protect the output with a password" — arming it (`"1"`) makes the host collect an
     * export-time secret with its **own dual masked fields** and send it on
     * [ExportSpec.exportSecret]; `"0"` or absent means no secret at all. The protection itself is
     * the **extension's** work — this is not a host-executed option, only a host-collected secret.
     *
     * [KIND_PASSPHRASE] keeps its never-crosses meaning untouched: this is a password for the file
     * being written, never the global Notesprout passphrase, never derived from it, never the
     * device key. See [ExportSpec.exportSecret] and [MAX_EXPORT_SECRET_CHARS].
     */
    const val OPTION_PROTECT: String = "protect"

    // ── Timeouts (host-side, over `ExtensionBinder.call`) ──────

    /** `describe()` returns a small in-memory descriptor — fast. */
    const val DESCRIBE_TIMEOUT_MS: Long = 3_000L

    /**
     * `export()` streams the whole artifact through two fds. A Binder call cannot be cancelled, so
     * this is sized generously against a big file on an e-ink CPU rather than guessed (the J5
     * lesson): measured on the Nomad 2026-08-27, a 100 MB flash copy lands in ~0.45 s (~525 MB/s
     * dd, ~230 MB/s cp) — two minutes covers a 1 GB artifact even at 10 MB/s through a slow
     * DocumentsProvider.
     *
     * One value for both source kinds. A [SOURCE_PAGES] export is a transform, not a copy, so it
     * was re-measured rather than assumed (arc 18, Nomad 2026-08-30 — twice: at D1 on the
     * framework assembly, 3.5 s / ~270 ms a page, and again at D3 when the assembly moved onto
     * pdfbox, 2.6 s / ~200 ms a page for the same 13-page bundle) — two minutes covers a
     * ~400-page notebook, far past anything a hand writes. The host's render happens before this
     * call starts and never counts against it.
     */
    const val EXPORT_TIMEOUT_MS: Long = 120_000L
}
