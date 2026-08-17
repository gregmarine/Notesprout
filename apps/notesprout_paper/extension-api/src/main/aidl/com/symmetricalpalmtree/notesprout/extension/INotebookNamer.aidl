package com.symmetricalpalmtree.notesprout.extension;

import com.symmetricalpalmtree.notesprout.extension.IExtensionStore;
import com.symmetricalpalmtree.notesprout.extension.SchemeField;

/**
 * The NOTEBOOK_NAMER extension point: per-folder naming schemes for new notebooks. The host draws
 * the one text field the extension describes; the extension owns the scheme language and keeps its
 * data in the host-owned store it is handed per call. Called on Binder threads; hold no state.
 */
interface INotebookNamer {
    /** How the host should draw the scheme field (label, hint, one help line). No store needed. */
    SchemeField describeField();

    /** The scheme stored for [folderId], or null if none. */
    String currentScheme(IExtensionStore store, String folderId);

    /** null if [scheme] is acceptable, else a short user-facing error. Pure — no store. */
    String validateScheme(String scheme);

    /** Store [scheme] for [folderId]; "" (or blank) clears it. Throws IllegalArgumentException if invalid. */
    void saveScheme(IExtensionStore store, String folderId, String scheme);

    /** The default name for a new notebook in [folderId] given the folder's existing notebook names,
     *  or null if the folder has no scheme (host then uses its own default). */
    String defaultName(IExtensionStore store, String folderId, in List<String> siblingNames);
}
