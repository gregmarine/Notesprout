// INotebookImporter.aidl — Notesprout SN's FOURTH extension point (arc 16 / I1).
// The exporter seam, reversed: the host keys (probe, unlock, re-key to the device global key,
// placement, remap, Garden + index writes) and the extension only streams the user's picked
// document (read fd) into a host-owned cache file (write fd). No passphrase, no path, no
// SQLCipher ever crosses. Stateless — one bind per call (ExtensionBinder.call), no held binding.
package com.symmetricalpalmtree.notesproutsn.extension;

import com.symmetricalpalmtree.notesproutsn.extension.ImporterInfo;
import com.symmetricalpalmtree.notesproutsn.extension.ImportSpec;
import com.symmetricalpalmtree.notesproutsn.extension.ImportResult;

interface INotebookImporter {
    /** The formats this importer accepts: label, file extensions, MIME types (caps in
     *  ImporterContract/ExporterContract — a descriptor over them fails at unmarshal and the host
     *  drops this importer). Fast; never touches storage. */
    ImporterInfo describe();

    /** Deliver the import: stream [source] (the user's picked document — untrusted bytes the host
     *  will probe and validate after) to [destination] (a host-owned cache file), and report the
     *  bytes written (the host verifies them against the source's size where the fd will say).
     *  The extension closes both descriptors before returning, success or not. Throws
     *  IllegalArgumentException for a spec it cannot serve, IllegalStateException on a delivery
     *  failure — the ONLY marshalable exceptions besides the caller check's SecurityException;
     *  anything else kills the transaction silently and the host would read garbage as failure. */
    // Named importDocument, not import — `import` is a reserved word in the generated Java.
    ImportResult importDocument(in ParcelFileDescriptor source, in ParcelFileDescriptor destination, in ImportSpec spec);
}
