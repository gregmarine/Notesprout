// IHandwritingRecognizer.aidl — the HANDWRITING_RECOGNIZER point (arc 3).
// Engine-neutral. Every argument is bare geometry; every result is plain text. Stateless.
package com.symmetricalpalmtree.notesprout.extension;

import com.symmetricalpalmtree.notesprout.extension.InkStroke;

interface IHandwritingRecognizer {
    /** One of RecognizerStatus.* — READY / NEEDS_DOWNLOAD / DOWNLOADING / UNAVAILABLE. Fast; never
     *  waits on the engine. DOWNLOADING covers everything in flight (checking, downloading, loading). */
    int status();

    /** Start acquiring what the engine needs (model download). Returns at once; poll status().
     *  A no-op while READY or already DOWNLOADING. The ONLY call that may start a download — the
     *  host asks the user first; recognize* wait for an acquisition already in flight but never
     *  start one (NEEDS_DOWNLOAD → call prepare()). */
    void prepare();

    /** Recognize one writing area (no layout analysis). [strokes] in the area's px space,
     *  [areaWidth]/[areaHeight] > 0, [preContext] = the text just before this ink ("" if none).
     *  Returns the top candidate ("" if none). If not READY but an acquisition is in flight, waits
     *  for it within the caller's timeout; throws IllegalStateException with message
     *  ExtensionContract.RECOGNIZER_NOT_READY if it cannot become ready (or nothing was prepared),
     *  any other IllegalStateException on an engine failure / timeout, IllegalArgumentException over
     *  the MAX_INK_* caps. */
    String recognizeInk(in List<InkStroke> strokes, float areaWidth, float areaHeight, String preContext);

    /** Recognize a whole page: the engine finds lines / paragraphs itself and chains context.
     *  [strokes] in page px; [pageWidth]/[pageHeight] the page size. Returns lines joined by '\n',
     *  paragraphs separated by a blank line ("" if nothing recognizable). Same exceptions. */
    String recognizePage(in List<InkStroke> strokes, float pageWidth, float pageHeight);
}
