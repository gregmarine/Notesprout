package com.symmetricalpalmtree.notesproutsn.extension

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The contract's exact values. The action string and the not-ready message are compared verbatim at
 * runtime (discovery filters, `RecognizerClient`'s message match) — a drift here is a silent
 * "no extension installed", so the strings are pinned by test.
 */
class ExtensionContractTest {

    @Test
    fun contractConstants() {
        // The declared meta-data is the version an extension REQUIRES of the host (a host accepts
        // 1..API_VERSION). 2 since arc 18 / D3 (the PDF exporter's sourceKind tail); 3 since
        // arc 19 / M8 (the text importer's ImporterInfo.resultKind tail — a version-2 host would
        // read text bytes as a .soil); 4 since arc 21 / W1 (the TAG_MANAGER point — an older host
        // knows no `ITagManager` at all); 5 since arc 21 / W4 (TagShowing's wire form — the first
        // non-tail break); 6 since arc 22 / X1 (IExtensionStore REPLACED — the second non-tail
        // break, and the first with a floor); 7 since arc 23 / Y1 (the CALENDAR point — a compatible
        // addition, with the floor made per action); 8 since arc 25 / V1 (the CLOUD_STORAGE point —
        // a compatible addition on the calendar's pattern, floored at 8); 9 since arc 31 / HV1
        // (ExporterInfo's `delivery` tail + the ICalendar render methods — a compatible tail whose
        // bump is the skew guard for per-page exporters, no floor moved); 10 since arc 35 / HA1
        // (ICalendar.advanceOutgoing — a Day send parks both halves; a compatible tail with a
        // method floor, no action floor moved); 11 since arc 37 / B0 (the BIBLE point — a
        // compatible addition on the calendar's pattern, floored at 11). Bumping this again is a
        // contract event.
        // 12 since arc 38 / R1 (two `IBible` tails behind a method floor; no action floor moved);
        // 13 since B9 "Send" (`takeOutgoingReference`); 14 since arc 39 / K1
        // (`IDocumentHost.openReference`); 15 since arc 40 "Verses" (`IBible.passageText`);
        // 16 since arc 42 "Notes" (seven `IBible` tails); 17 since arc 43 / K2 (the SKETCH point —
        // SN's TENTH, a compatible addition on the Bible's pattern, floored at 17); 18 since arc 43
        // / K5b (three `ISketchHost` tails — insertPage / deletePage / pageContent — behind the
        // METHOD floor MIN_API_VERSION_FOR_SKETCH_PAGES; no action floor moved, only :ext-sketch
        // redeclares); 19 since arc 44 "Pencils" / T2 (two more `ISketchHost` tails —
        // toolSettings / putToolSettings — behind the METHOD floor
        // MIN_API_VERSION_FOR_SKETCH_TOOLS; no action floor moved, only :ext-sketch redeclares);
        // 20 since arc 45 "Ink" / G2 (two rasters — readSketchChunk / saveSketchChunk take a layer
        // IN PLACE at codes 3–4, SketchPageState carries two byte/chunk pairs, the guard is WebP:
        // the THIRD non-tail break, and the sketch ACTION floor moved 17 → 20 with it — no legacy,
        // the user's decision 4; only :ext-sketch redeclares); 21 since arc 46 "Palette" / Q1
        // (SketchToolSettings grows a fourth int, penShade — a PARCEL tail read with the
        // exhausted-parcel rule; no method, no floor moved, only :ext-sketch redeclares).
        assertEquals(21, ExtensionContract.API_VERSION)
        assertEquals(9, ExporterContract.MIN_API_VERSION_FOR_DELIVERY)
        assertEquals(6, ExtensionContract.MIN_API_VERSION_FOR_STORE)
        assertEquals(7, ExtensionContract.MIN_API_VERSION_FOR_CALENDAR)
        // HV4: the render is a METHOD floor under 9 — the map above is untouched.
        assertEquals(9, ExtensionContract.MIN_API_VERSION_FOR_CALENDAR_RENDER)
        // Floors pin to their birth number, never to API_VERSION (the HV1 lesson).
        assertEquals(10, ExtensionContract.MIN_API_VERSION_FOR_CALENDAR_DAY_SEND)
        assertEquals(11, ExtensionContract.MIN_API_VERSION_FOR_BIBLE)
        assertEquals(12, ExtensionContract.MIN_API_VERSION_FOR_BIBLE_REFERENCE)
        assertEquals(13, ExtensionContract.MIN_API_VERSION_FOR_BIBLE_SEND)
        assertEquals(15, ExtensionContract.MIN_API_VERSION_FOR_BIBLE_TEXT)
        assertEquals(16, ExtensionContract.MIN_API_VERSION_FOR_BIBLE_NOTES)
        // G2: the sketch action floor is the one floor that moved after birth — 17 → 20. The two
        // method floors keep their birth numbers as history and sit below it, inert.
        assertEquals(20, SketchContract.MIN_API_VERSION_FOR_SKETCH)
        assertEquals(18, SketchContract.MIN_API_VERSION_FOR_SKETCH_PAGES)
        assertEquals(19, SketchContract.MIN_API_VERSION_FOR_SKETCH_TOOLS)
        // Q1: the pen-shade tail is named at 21 — above the action floor, a live gate in the sense
        // that :ext-sketch declares it; the action floor itself did not move.
        assertEquals(21, SketchContract.MIN_API_VERSION_FOR_SKETCH_PEN_SHADE)
        assertEquals(14, DocumentContract.MIN_API_VERSION_FOR_DOCUMENT_LOOKUP)
        assertEquals(2_000, ExtensionContract.MAX_INK_STROKES)
        assertEquals(60_000, ExtensionContract.MAX_INK_POINTS)
        assertEquals(20, ExtensionContract.MAX_PRECONTEXT_CHARS)
        assertEquals(20_000, ExtensionContract.MAX_RECOGNIZED_CHARS)
        assertEquals("recognizer not ready", ExtensionContract.RECOGNIZER_NOT_READY)
    }

    @Test
    fun actionStringsAreSnNamespaced() {
        // SN and Paper extensions coexist on the Nomad under the same debug signature — only the
        // namespace keeps each family discovering its own.
        assertEquals(
            "com.symmetricalpalmtree.notesproutsn.extension.HANDWRITING_RECOGNIZER",
            ExtensionContract.ACTION_HANDWRITING_RECOGNIZER,
        )
        assertEquals(
            "com.symmetricalpalmtree.notesproutsn.extension.API_VERSION",
            ExtensionContract.META_API_VERSION,
        )
        assertEquals(
            "com.symmetricalpalmtree.notesproutsn.extension.SCRATCH_PAD",
            ExtensionContract.ACTION_SCRATCH_PAD,
        )
        assertEquals(
            "com.symmetricalpalmtree.notesproutsn.extension.SCRATCH_PAD_SCREEN",
            ExtensionContract.ACTION_SCRATCH_PAD_SCREEN,
        )
        assertEquals(
            "com.symmetricalpalmtree.notesproutsn.extension.TAG_MANAGER",
            ExtensionContract.ACTION_TAG_MANAGER,
        )
        assertEquals(
            "com.symmetricalpalmtree.notesproutsn.extension.TAG_MANAGER_SCREEN",
            ExtensionContract.ACTION_TAG_MANAGER_SCREEN,
        )
        assertEquals(
            "com.symmetricalpalmtree.notesproutsn.extension.CALENDAR",
            ExtensionContract.ACTION_CALENDAR,
        )
        assertEquals(
            "com.symmetricalpalmtree.notesproutsn.extension.CALENDAR_SCREEN",
            ExtensionContract.ACTION_CALENDAR_SCREEN,
        )
        assertEquals(
            "com.symmetricalpalmtree.notesproutsn.extension.CLOUD_STORAGE",
            CloudContract.ACTION_CLOUD_STORAGE,
        )
        assertEquals(
            "com.symmetricalpalmtree.notesproutsn.extension.BIBLE",
            ExtensionContract.ACTION_BIBLE,
        )
        assertEquals(
            "com.symmetricalpalmtree.notesproutsn.extension.BIBLE_SCREEN",
            ExtensionContract.ACTION_BIBLE_SCREEN,
        )
        assertEquals(
            "com.symmetricalpalmtree.notesproutsn.extension.CLOUD_STORAGE_SCREEN",
            CloudContract.ACTION_CLOUD_STORAGE_SCREEN,
        )
        assertEquals(
            "com.symmetricalpalmtree.notesproutsn.extension.SKETCH",
            SketchContract.ACTION_SKETCH,
        )
        assertEquals(
            "com.symmetricalpalmtree.notesproutsn.extension.SKETCH_SCREEN",
            SketchContract.ACTION_SKETCH_SCREEN,
        )
    }

    /** The calendar's launch extras and result code (arc 23 / Y1) — the pad's three, mirrored,
     *  with names that cannot collide with the pad's on a host that opens both. */
    @Test
    fun calendarConstants() {
        assertEquals("calendarSendEnabled", ExtensionContract.EXTRA_CALENDAR_SEND_ENABLED)
        assertEquals("calendarOpenReceived", ExtensionContract.EXTRA_CALENDAR_OPEN_RECEIVED)
        assertEquals(1, ExtensionContract.RESULT_CALENDAR_SEND)
        assertTrue(ExtensionContract.EXTRA_CALENDAR_SEND_ENABLED != ExtensionContract.EXTRA_SCRATCH_SEND_ENABLED)
        assertTrue(ExtensionContract.EXTRA_CALENDAR_OPEN_RECEIVED != ExtensionContract.EXTRA_SCRATCH_OPEN_RECEIVED)
    }

    /** The tag caps (arc 21 / W1) and the paging numbers (arc 22 / X3). The caps are the wizard's
     *  and are **policy** now — a `COUNT(*)` check inside the insert that would break one — where
     *  arc 21's arithmetic had to prove the worst legal index fitted one store value. The paging
     *  numbers are not policy: they are what a Binder reply parcel can carry. */
    @Test
    fun tagConstants() {
        assertEquals(64, ExtensionContract.MAX_TAG_CHARS)
        assertEquals(5_000, ExtensionContract.MAX_TAGS)
        assertEquals(50_000, ExtensionContract.MAX_TAG_ASSIGNMENTS)
        assertEquals(200, ExtensionContract.MAX_TARGET_LABEL_CHARS)
        assertEquals("tag index full", ExtensionContract.TAG_INDEX_FULL)
        // Since W4 a target id has no length cap of its own: it is a canonical UUID or it is not a
        // target ([TagRules.isId] at every door).
        // The paging numbers (arc 22 / X3): a reply is an ordinary parcel, so both listings page.
        assertEquals(500, ExtensionContract.TAGS_PAGE)
        assertEquals(1_000, ExtensionContract.ASSIGNMENTS_PAGE)
        assertEquals(500, ExtensionContract.ASSIGNMENT_QUERY_TAGS)
        // One `IN (…)` list of that many ids plus the LIMIT/OFFSET binds must fit SQLite's own cap.
        assertTrue(ExtensionContract.ASSIGNMENT_QUERY_TAGS + 2 <= ExtensionContract.STORE_MAX_ARGS)
    }

    /** The scratch-pad transfer values: Paper's **shipped** constants (its S2 outcome), not the
     *  pre-S2 table in its plan appendix — copying the wrong ones silently halves every transfer. */
    @Test
    fun scratchPadConstants() {
        assertEquals("sendEnabled", ExtensionContract.EXTRA_SCRATCH_SEND_ENABLED)
        assertEquals("openReceived", ExtensionContract.EXTRA_SCRATCH_OPEN_RECEIVED)
        assertEquals(1, ExtensionContract.RESULT_SCRATCH_SEND)
        assertEquals(0, ExtensionContract.PLACEMENT_NEW_PAGE)
        assertEquals(1, ExtensionContract.PLACEMENT_CURRENT_PAGE)
        assertEquals(10_000, ExtensionContract.MAX_TRANSFER_STROKES)
        assertEquals(400_000, ExtensionContract.MAX_TRANSFER_POINTS)
        assertEquals(300, ExtensionContract.TRANSFER_CHUNK_STROKES)
        assertEquals(20_000, ExtensionContract.TRANSFER_CHUNK_POINTS)
        // Not a hand-picked number: the bound counts BOTH reasons a chunk closes (see the constant).
        assertEquals(74, ExtensionContract.TRANSFER_MAX_CHUNKS)
        // A chunk must never be able to exceed a whole transfer.
        assertTrue(ExtensionContract.TRANSFER_CHUNK_STROKES <= ExtensionContract.MAX_TRANSFER_STROKES)
        assertTrue(ExtensionContract.TRANSFER_CHUNK_POINTS <= ExtensionContract.MAX_TRANSFER_POINTS)
    }

    /** The store's caps and its typed messages (extensions compare them verbatim, not by substring). */
    @Test
    fun storeConstants() {
        assertEquals(512 * 1024, ExtensionContract.STORE_MAX_INLINE_BYTES)
        assertEquals(4 * 1024 * 1024, ExtensionContract.STORE_MAX_VALUE_BYTES)
        assertEquals(32 * 1024 * 1024, ExtensionContract.STORE_MAX_RESULT_BYTES)
        assertEquals(ExtensionContract.STORE_MAX_VALUE_BYTES, ExtensionContract.STORE_MAX_ROW_BYTES)
        assertEquals(10_000, ExtensionContract.STORE_MAX_BATCH_STATEMENTS)
        assertEquals(8_192, ExtensionContract.STORE_MAX_SQL_CHARS)
        assertEquals(999, ExtensionContract.STORE_MAX_ARGS)
        assertEquals(64, ExtensionContract.STORE_MAX_TABLES)
        assertEquals(256, ExtensionContract.STORE_MAX_SCHEMA_STEPS)
        assertEquals(64, ExtensionContract.STORE_MAX_STEP_STATEMENTS)
        assertEquals(4, ExtensionContract.STORE_MAX_OPEN_RESULTS)
        assertEquals("store result large", ExtensionContract.STORE_RESULT_LARGE)
        assertEquals("store row large", ExtensionContract.STORE_ROW_LARGE)
        assertEquals("store schema newer", ExtensionContract.STORE_SCHEMA_NEWER)
        assertEquals("store schema unapplied", ExtensionContract.STORE_SCHEMA_UNAPPLIED)
        assertEquals("store results open", ExtensionContract.STORE_RESULTS_OPEN)
        // The inline carrier must be a strict subset of the region one, or a payload could be too
        // big to ride inline and too big for a region at once.
        assertTrue(ExtensionContract.STORE_MAX_INLINE_BYTES < ExtensionContract.STORE_MAX_VALUE_BYTES)
        // A chunk is a payload; a result is at least a chunk.
        assertTrue(ExtensionContract.STORE_MAX_ROW_BYTES <= ExtensionContract.STORE_MAX_VALUE_BYTES)
        assertTrue(ExtensionContract.STORE_MAX_VALUE_BYTES <= ExtensionContract.STORE_MAX_RESULT_BYTES)
    }

    /** The floor rule (arc 22 / X1, per action since arc 23 / Y1): a store-taking point's service
     *  is listed only at 6 and above, the calendar's only at 7, and the stateless points keep
     *  accepting 1..API_VERSION. No existing door moved when the calendar arrived. */
    @Test
    fun storeTakingPointsHaveTheFloor() {
        for (action in listOf(
            ExtensionContract.ACTION_SCRATCH_PAD,
            DocumentContract.ACTION_DOCUMENT_EDITOR,
            ExtensionContract.ACTION_TAG_MANAGER,
        )) {
            assertEquals(action, 6, ExtensionContract.minApiVersion(action))
            assertTrue(action, !ExtensionContract.accepts(action, 5))
            assertTrue(action, ExtensionContract.accepts(action, 6))
            assertTrue(action, ExtensionContract.accepts(action, 7))
            assertTrue(action, ExtensionContract.accepts(action, 8))
            assertTrue(action, !ExtensionContract.accepts(action, ExtensionContract.API_VERSION + 1))
        }
        val calendar = ExtensionContract.ACTION_CALENDAR
        assertEquals(7, ExtensionContract.minApiVersion(calendar))
        assertTrue(!ExtensionContract.accepts(calendar, 6))
        assertTrue(ExtensionContract.accepts(calendar, 7))
        assertTrue(ExtensionContract.accepts(calendar, 8))
        assertTrue(!ExtensionContract.accepts(calendar, ExtensionContract.API_VERSION + 1))
        // The cloud point (arc 25 / V1): born at 8, listed only there. The calendar's door did not move.
        val cloud = CloudContract.ACTION_CLOUD_STORAGE
        assertEquals(8, ExtensionContract.minApiVersion(cloud))
        assertEquals(CloudContract.MIN_API_VERSION_FOR_CLOUD, ExtensionContract.minApiVersion(cloud))
        assertTrue(!ExtensionContract.accepts(cloud, 7))
        assertTrue(ExtensionContract.accepts(cloud, 8))
        assertTrue(!ExtensionContract.accepts(cloud, ExtensionContract.API_VERSION + 1))
        // The Bible point (arc 37 / B0): born at 11, listed only there. No other door moved.
        val bible = ExtensionContract.ACTION_BIBLE
        assertEquals(11, ExtensionContract.minApiVersion(bible))
        assertEquals(ExtensionContract.MIN_API_VERSION_FOR_BIBLE, ExtensionContract.minApiVersion(bible))
        assertTrue(!ExtensionContract.accepts(bible, 10))
        assertTrue(ExtensionContract.accepts(bible, 11))
        assertTrue(!ExtensionContract.accepts(bible, ExtensionContract.API_VERSION + 1))
        // The Sketch point (arc 43 / K2): born at 17; listed at 20 since arc 45 / G2, when its own
        // chunk calls changed shape in place — a 17–19 screen is not discovered, by design (no
        // legacy). No other door moved — every floor above still answers its own birth number.
        val sketch = SketchContract.ACTION_SKETCH
        assertEquals(20, ExtensionContract.minApiVersion(sketch))
        assertEquals(SketchContract.MIN_API_VERSION_FOR_SKETCH, ExtensionContract.minApiVersion(sketch))
        for (old in 16..19) assertTrue("a $old sketch screen must not bind", !ExtensionContract.accepts(sketch, old))
        assertTrue(ExtensionContract.accepts(sketch, 20))
        // Q1: a 21 screen (the pen-shade parcel tail) binds too — a tail never closes a door.
        assertTrue(ExtensionContract.accepts(sketch, 21))
        assertTrue(!ExtensionContract.accepts(sketch, ExtensionContract.API_VERSION + 1))
        // The screen action is not a service action — it carries no floor of its own.
        assertEquals(1, ExtensionContract.minApiVersion(SketchContract.ACTION_SKETCH_SCREEN))
        assertEquals(1, ExtensionContract.minApiVersion(ExtensionContract.ACTION_BIBLE_SCREEN))
        assertEquals(1, ExtensionContract.minApiVersion(ExtensionContract.ACTION_CALENDAR_SCREEN))
        assertEquals(1, ExtensionContract.minApiVersion(CloudContract.ACTION_CLOUD_STORAGE_SCREEN))
        for (action in listOf(
            ExtensionContract.ACTION_HANDWRITING_RECOGNIZER,
            ExporterContract.ACTION_NOTEBOOK_EXPORTER,
            ImporterContract.ACTION_NOTEBOOK_IMPORTER,
        )) {
            assertEquals(action, 1, ExtensionContract.minApiVersion(action))
            assertTrue(action, ExtensionContract.accepts(action, 1))
            assertTrue(action, ExtensionContract.accepts(action, ExtensionContract.API_VERSION))
            assertTrue(action, !ExtensionContract.accepts(action, 0))
            assertTrue(action, !ExtensionContract.accepts(action, ExtensionContract.API_VERSION + 1))
        }
    }

    @Test
    fun statusConstants() {
        assertEquals(0, RecognizerStatus.READY)
        assertEquals(1, RecognizerStatus.NEEDS_DOWNLOAD)
        assertEquals(2, RecognizerStatus.DOWNLOADING)
        assertEquals(3, RecognizerStatus.UNAVAILABLE)
    }

    @Test
    fun calendarRenderConstantsArePinned() {
        // Arc 31 / HV4. The four flags are distinct bits, RENDER_ALL is exactly their union, the
        // result code is the next after the pad door, and the extra is the Intent's FOURTH boolean —
        // every value here is read by the calendar extension, so a change is a contract event.
        assertEquals(1, ExtensionContract.RENDER_GRID)
        assertEquals(2, ExtensionContract.RENDER_INK)
        assertEquals(4, ExtensionContract.RENDER_RING)
        assertEquals(8, ExtensionContract.RENDER_MARKS)
        assertEquals(15, ExtensionContract.RENDER_ALL)
        assertEquals(3, ExtensionContract.RESULT_CALENDAR_EXPORT)
        assertEquals("calendarExportEnabled", ExtensionContract.EXTRA_CALENDAR_EXPORT_ENABLED)
        assertEquals(8, ExtensionContract.RENDER_MAX_TARGETS)
        assertEquals(30_000L, ExtensionContract.CALENDAR_RENDER_TIMEOUT_MS)   // measured on the Nomad (HV4 ledger)
        // The floor is a method floor: the calendar ACTION still accepts a 7.
        assertEquals(true, ExtensionContract.accepts(ExtensionContract.ACTION_CALENDAR, 7))
        assertEquals(true, ExtensionContract.accepts(ExtensionContract.ACTION_CALENDAR, 9))
    }

    @Test
    fun chromeExtraIsPinned() {
        // Arc 33 / F3. Read by both ink screens and written by the host on the way out AND read
        // back on the way in — a drift here is a flag that silently stops crossing.
        assertEquals("chromeHidden", ExtensionContract.EXTRA_CHROME_HIDDEN)
        // A compatible tail: no floor moved for either point that carries it.
        assertEquals(ExtensionContract.MIN_API_VERSION_FOR_STORE, ExtensionContract.minApiVersion(ExtensionContract.ACTION_SCRATCH_PAD))
        assertEquals(ExtensionContract.MIN_API_VERSION_FOR_CALENDAR, ExtensionContract.minApiVersion(ExtensionContract.ACTION_CALENDAR))
    }

    @Test
    fun penShadeExtraIsPinned() {
        // Arc 49 / P4. The chrome flag's shape for the pen's shade: out on the launch Intent, back
        // on the result, an int level on the sixteen-tone ladder — a drift here is a grey that
        // silently stops crossing, and a pad that opens black under a grey notebook.
        assertEquals("penShade", ExtensionContract.EXTRA_PEN_SHADE)
        // Not a version: no floor moved and API_VERSION did not, for either point that carries it.
        assertEquals(ExtensionContract.MIN_API_VERSION_FOR_STORE, ExtensionContract.minApiVersion(ExtensionContract.ACTION_SCRATCH_PAD))
        assertEquals(ExtensionContract.MIN_API_VERSION_FOR_CALENDAR, ExtensionContract.minApiVersion(ExtensionContract.ACTION_CALENDAR))
    }
}
