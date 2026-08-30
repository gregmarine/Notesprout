// INotebookExporter.aidl — Notesprout SN's THIRD extension point (arc 15 / E1, grown arc 18 / D1).
// The host keys, the extension delivers: everything that touches a key (checkpoint, keying
// transform, SAF destination) runs host-side. What the read fd carries is the descriptor's
// sourceKind (ExporterInfo's compatible tail — absent means SOURCE_SOIL): the prepared artifact,
// streamed verbatim, or a host-rendered PageBundle for an exporter that transforms (a PDF) and so
// can never receive the .soil. No passphrase, no path, no SQLCipher ever crosses — with ONE
// deliberate, bounded exception: ExportSpec.exportSecret, a user-typed, export-scoped password
// for the OUTPUT file (arc 18 / D2, OPTION_PROTECT), which opens no Notesprout data and is never
// the global passphrase or the device key. Stateless — one bind per call (ExtensionBinder.call),
// no held binding.
package com.symmetricalpalmtree.notesproutsn.extension;

import com.symmetricalpalmtree.notesproutsn.extension.ExporterInfo;
import com.symmetricalpalmtree.notesproutsn.extension.ExportSpec;
import com.symmetricalpalmtree.notesproutsn.extension.ExportResult;

interface INotebookExporter {
    /** The one format this exporter offers: label, file extension, MIME, a bounded option list
     *  the host renders with its own widgets, and the sourceKind tail naming what the read fd
     *  must carry (caps in ExporterContract — a descriptor over them, or declaring an unknown
     *  source kind, fails at unmarshal and the host drops this exporter). The descriptor must
     *  stay the reply's TRAILING payload: the tail is detected by dataAvail(), and a later
     *  version may append further fields. Fast; never touches storage. */
    ExporterInfo describe();

    /** Produce the export: turn [source] (what the descriptor's sourceKind asked for — the
     *  host-prepared artifact, already keyed as the spec chose, or a PageBundle of host-rendered
     *  pages) into [destination], and report the bytes actually written there — a MEASURED count,
     *  never a guess and never the source's length. Verification is per source kind
     *  (ExportVerification): SOURCE_SOIL is verbatim streaming, so the count must equal the
     *  artifact's size; SOURCE_PAGES is a transform, so the count is corroborated against the
     *  destination's own answers only — an author writing to the old equality would fail every
     *  honest transform. [spec] must stay the call's TRAILING argument (its exportSecret rides a
     *  compatible tail). The extension closes both descriptors before returning, success or not,
     *  and drops any exportSecret reference in its own finally. Throws IllegalArgumentException
     *  for a spec it cannot serve, IllegalStateException on a delivery failure — the ONLY
     *  marshalable exceptions besides the caller check's SecurityException; anything else kills
     *  the transaction silently and the host would read garbage as failure. */
    ExportResult export(in ParcelFileDescriptor source, in ParcelFileDescriptor destination, in ExportSpec spec);
}
