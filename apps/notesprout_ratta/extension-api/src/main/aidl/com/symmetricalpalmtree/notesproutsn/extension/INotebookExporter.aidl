// INotebookExporter.aidl — Notesprout SN's THIRD extension point (arc 15 / E1).
// The host keys, the extension delivers: everything that touches a key (checkpoint, keying
// transform, SAF destination) runs host-side; the extension turns a prepared artifact (read fd)
// into the destination (write fd). No passphrase, no path, no SQLCipher ever crosses. Stateless —
// one bind per call (ExtensionBinder.call), no held binding.
package com.symmetricalpalmtree.notesproutsn.extension;

import com.symmetricalpalmtree.notesproutsn.extension.ExporterInfo;
import com.symmetricalpalmtree.notesproutsn.extension.ExportSpec;
import com.symmetricalpalmtree.notesproutsn.extension.ExportResult;

interface INotebookExporter {
    /** The one format this exporter offers: label, file extension, MIME, and a bounded option
     *  list the host renders with its own widgets (caps in ExporterContract — a descriptor over
     *  them fails at unmarshal and the host drops this exporter). Fast; never touches storage. */
    ExporterInfo describe();

    /** Produce the export: stream [source] (the host-prepared artifact, already keyed as the spec
     *  chose — the extension cannot tell and must not care) to [destination], and report the bytes
     *  written (the host verifies them against the artifact's size before claiming success).
     *  The extension closes both descriptors before returning, success or not. Throws
     *  IllegalArgumentException for a spec it cannot serve, IllegalStateException on a delivery
     *  failure — the ONLY marshalable exceptions besides the caller check's SecurityException;
     *  anything else kills the transaction silently and the host would read garbage as failure. */
    ExportResult export(in ParcelFileDescriptor source, in ParcelFileDescriptor destination, in ExportSpec spec);
}
