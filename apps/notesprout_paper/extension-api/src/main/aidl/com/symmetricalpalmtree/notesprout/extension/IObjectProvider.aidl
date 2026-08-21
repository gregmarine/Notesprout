// IObjectProvider.aidl — the OBJECT_PROVIDER point (arc 4 / H3). A provider owns one or more object
// types (typeIds). The core stores an opaque payload per object and asks the provider to act on it:
// describe actions · create-from-ink · apply an action · describe / apply an edit · render. The
// capabilities a provider needs (recognizer, markdown) reach it ONLY as in-parameters — the core's
// proxies, or null when the capability is not installed.
package com.symmetricalpalmtree.notesprout.extension;

import com.symmetricalpalmtree.notesprout.extension.SelectionAction;
import com.symmetricalpalmtree.notesprout.extension.EditSpec;
import com.symmetricalpalmtree.notesprout.extension.CreatedObject;
import com.symmetricalpalmtree.notesprout.extension.RenderedImage;
import com.symmetricalpalmtree.notesprout.extension.InkStroke;
import com.symmetricalpalmtree.notesprout.extension.OutlineEntry;
import com.symmetricalpalmtree.notesprout.extension.IHandwritingRecognizer;
import com.symmetricalpalmtree.notesprout.extension.IMarkdownRenderer;

interface IObjectProvider {
    /** The typeIds this provider owns ([a-z0-9_-]+, ≤ MAX_TYPE_ID_CHARS, ≤ 16). Pure. */
    List<String> describeTypes();

    /** Selection-toolbar contributions in display order (≤ MAX_ACTIONS; one level of sub-actions). Pure. */
    List<SelectionAction> describeActions();

    /** For a selected object: which of this provider's action ids are "active" (drawn selected —
     *  e.g. the heading's current level). Pure; empty if none. */
    List<String> activeActionIds(String typeId, String payload);

    /** Turn a pure-stroke selection into an object. [actionId] = the tapped leaf action; [strokes] in
     *  page px, [areaWidth]/[areaHeight] = the selection bounds' size; [recognizer] = the core's proxy or
     *  null when none is installed (throw IllegalStateException(RECOGNIZER_REQUIRED) if it is needed).
     *  Returns the new object's typeId + payload, or null when nothing usable was recognized. */
    CreatedObject createFromInk(String actionId, in List<InkStroke> strokes, float areaWidth, float areaHeight,
                                IHandwritingRecognizer recognizer);

    /** Apply a leaf action to an existing object. Returns the new payload, or null for "no change". Pure. */
    String applyAction(String actionId, String typeId, String payload);

    /** How the core should draw the edit dialog for this object (null = not editable). Pure. */
    EditSpec describeEdit(String typeId, String payload);

    /** The payload after the user saved [text] in the edit dialog; null = no change (e.g. blank). Pure. */
    String applyEdit(String typeId, String payload, String text);

    /** Render the object: [maxWidthPx] > 0 (page width minus the object's x), [dpi] the panel density,
     *  [markdown] = the core's proxy or null when none is installed (throw
     *  IllegalStateException(MARKDOWN_REQUIRED) if it is needed). Returns null if there is nothing to draw. */
    RenderedImage render(String typeId, String payload, int maxWidthPx, float dpi, IMarkdownRenderer markdown);

    // ── arc 5 / C0 — appended after render(); the eight methods above keep their transaction codes ──

    /** Outline (table-of-contents) entries for [payloads] of one of this provider's types — one
     *  OutlineEntry per payload, same order, same length: level 1..MAX_OUTLINE_LEVEL with a label
     *  ≤ MAX_OUTLINE_LABEL_CHARS, or level 0 (label ignored) for "not an outline item". Pure, ≤ 2 s;
     *  the host chunks at MAX_OUTLINE_BATCH / MAX_OUTLINE_BATCH_CHARS per call. A provider built
     *  before this method existed simply never receives it (the host tolerates the failure). */
    List<OutlineEntry> describeOutline(String typeId, in List<String> payloads);
}
