package com.symmetricalpalmtree.notesprout.extension;

import com.symmetricalpalmtree.notesprout.extension.CatalogEntry;

/**
 * The LINK_PROVIDER point's catalog callback (arc 7 / L0) — the first HOST-implemented multi-method
 * binder: a per-showing, uid-gated lens over the host's library the picker screen browses through.
 * Handed to the extension only as the second argument of ILinkProvider.beginPick and dead outside
 * that showing (revoked with the bind — a late call throws SecurityException). Outward = names, ids
 * and labels of alive rows only; never keys, paths, covers or blobs (rule 29).
 *
 * Every method may throw IllegalArgumentException (unknown/dead id, invalid name — the message is
 * user-honest and the picker shows it) or IllegalStateException (the host could not read/write).
 * The host runs the reads/writes on its own Binder thread (never Main); the extension shows its own
 * progress while it waits.
 */
interface ILinkCatalog {
    /** Alive folders + notebooks under [folderId] ("" = the library root), in library order —
     *  folders first, then notebooks, ≤ MAX_CATALOG_ENTRIES per reply. */
    List<CatalogEntry> listFolder(String folderId);

    /** The page rows of [notebookId] in page order — kind CATALOG_PAGE, blank labels allowed (the
     *  picker shows "Page n" from position). ≤ MAX_CATALOG_ENTRIES per reply. */
    List<CatalogEntry> listPages(String notebookId);

    // ── The create half (arc 7 / L3) — UnsupportedOperationException until then. The host validates
    // exactly as its own library UI would; a refusal is an IllegalArgumentException whose message the
    // picker shows verbatim in a problem dialog. ──

    /** Create a blank page in [notebookId] — before/after [anchorPageId], or appended when the anchor
     *  is "" — and return the new page id. (L3) */
    String createPage(String notebookId, String anchorPageId, boolean before);

    /** Create a folder named [name] under [parentFolderId] ("" = root) and return its id. (L3) */
    String createFolder(String parentFolderId, String name);

    /** Create a blank notebook named [name] under [parentFolderId] ("" = root) and return its id. (L3) */
    String createNotebook(String parentFolderId, String name);
}
