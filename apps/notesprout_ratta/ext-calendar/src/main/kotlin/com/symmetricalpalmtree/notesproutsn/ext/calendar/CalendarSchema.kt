package com.symmetricalpalmtree.notesproutsn.ext.calendar

import com.symmetricalpalmtree.notesproutsn.extension.StoreSchema
import com.symmetricalpalmtree.notesproutsn.ink.InkSql

/**
 * The calendar's tables in the host's extension store (arc 23 / Y1; grown arc 24 / Z1) — declared
 * once, applied by the host. Every statement is validated by `StoreSql.checkDdl` at construction,
 * so a mistake here fails on this side, at class-load, and never at bind.
 *
 * ```sql
 * -- step 1 (arc 23)
 * period (id, kind, date)                                   -- UNIQUE(kind, date); date = ISO day
 * page   (id, periodId → period.id ON DELETE CASCADE, half, width, height, createdAt, updatedAt)
 * stroke (id, pageId → page.id ON DELETE CASCADE, "order", color, width, style, blob)
 * state  (key, value)                                       -- lastView · lastDate · lastHalf
 * -- step 2 (arc 24 — events)
 * event           (id, type, title, startDate, endDate, allDay, startMinute, endMinute, recurring,
 *                  freq, interval, monthlyMode, endMode, untilDate, endCount,
 *                  noteText, noteWidth, noteHeight, createdAt, updatedAt)
 * event_weekday   (eventId → event.id ON DELETE CASCADE, weekday)      -- PK(eventId, weekday)
 * event_exception (eventId → event.id ON DELETE CASCADE, date)         -- PK(eventId, date)
 * event_reminder  (eventId → event.id ON DELETE CASCADE, amount, unit) -- PK(eventId, amount, unit)
 * note_stroke     (id, eventId → event.id ON DELETE CASCADE, "order", color, width, style, blob)
 * ```
 *
 * **The `stroke` half is `:ext-ink`'s** ([InkSql.CREATE_STROKE_TABLE] / [InkSql.CREATE_STROKE_INDEX],
 * arc 23) — one declaration for both consumers, byte-identical to what this object used to spell
 * out and pinned by `CalendarSqlTest`; only the calendar's own tables are written here.
 *
 * A `period` is a month (dated by its first day), a week (by its Sunday) or a day; the kind column
 * says which, so no key prefix does. A month or a week owns one `page` (`half` 0); a day owns two
 * (0 = AM, 1 = PM). **Rows are minted on the first stroke, never on open** — browsing empty months
 * writes nothing. `stroke` is the pad's row exactly (`StrokeCodec` format B in `blob`, `"order"`
 * the writing order within the page); `page.width/height` is the page's minted size, this device's
 * screen, so a template rendered at the page's own size keeps grid and ink registered on any
 * screen the store is later carried to.
 *
 * **Step 2 — events (arc 24 / Z1).** Columnar, no JSON, hard delete. An `event` is one row: its
 * dates are ISO `yyyy-MM-dd` text (which orders correctly as text, so a span overlap is
 * `startDate <= ? AND endDate >= ?`), its minutes are minute-of-day integers or NULL, and
 * `recurring` is a stored mirror of `freq IS NOT NULL` so the expansion read is an index hit.
 * `interval` / `monthlyMode` / `endMode` are NOT NULL on every row — a one-off carries the defaults
 * (`1` / `DAY_OF_MONTH` / `NEVER`). The three small child tables are sets keyed by their whole row:
 * a WEEKLY rule's weekdays (ISO 1 = Mon … 7 = Sun; none = the anchor's own weekday), the occurrence
 * STARTS removed from a series, and the reminders (`amount` × `unit` DAYS | WEEKS). `note_stroke`
 * is the pad's stroke row under its own name and parent — the event's one page of handwriting,
 * whose minted size is `event.noteWidth/noteHeight` (`0 × 0` until the first stroke).
 *
 * Foreign keys are ON for the store connection, so `DELETE FROM period` takes its pages and their
 * strokes with it, and `DELETE FROM event` takes its three child sets and its note — which is why
 * neither `period` nor `page` **nor `event`** is ever written with `INSERT OR REPLACE` (REPLACE
 * deletes the conflicting row first, and that delete cascades). Nothing deletes a `period`;
 * deleting an event is the one `DELETE` under the declared cascade.
 *
 * **A landed step is never edited.** [V1] is exactly what arc 23 shipped; [V2] is that step plus
 * the events step, and the host runs only the steps a store has not seen (`host_schema` 1 → 2 on
 * the first open after the upgrade, each step its own transaction with the version bump).
 */
object CalendarSchema {

    /** Arc 23's step — landed, and therefore never edited; [V2] builds on it. */
    val V1: StoreSchema = StoreSchema(
        version = 1,
        steps = listOf(
            listOf(
                """CREATE TABLE period (
                       id TEXT PRIMARY KEY,
                       kind INTEGER NOT NULL,
                       date TEXT NOT NULL,
                       UNIQUE(kind, date));""",
                """CREATE TABLE page (
                       id TEXT PRIMARY KEY,
                       periodId TEXT NOT NULL REFERENCES period(id) ON DELETE CASCADE,
                       half INTEGER NOT NULL,
                       width REAL NOT NULL,
                       height REAL NOT NULL,
                       createdAt INTEGER NOT NULL,
                       updatedAt INTEGER NOT NULL,
                       UNIQUE(periodId, half));""",
                InkSql.CREATE_STROKE_TABLE,
                InkSql.CREATE_STROKE_INDEX,
                "CREATE TABLE state (key TEXT PRIMARY KEY, value TEXT NOT NULL);",
            ),
        ),
    )

    /** The events step, arc 24 / Z1 — see the class doc. Every write to `event` is
     *  `INSERT OR IGNORE` + `UPDATE`, never `INSERT OR REPLACE` (the cascade would take the note). */
    private val EVENTS_STEP: List<String> = listOf(
        """CREATE TABLE event (
                   id TEXT PRIMARY KEY,
                   type TEXT NOT NULL,
                   title TEXT NOT NULL,
                   startDate TEXT NOT NULL,
                   endDate TEXT NOT NULL,
                   allDay INTEGER NOT NULL,
                   startMinute INTEGER,
                   endMinute INTEGER,
                   recurring INTEGER NOT NULL,
                   freq TEXT,
                   interval INTEGER NOT NULL,
                   monthlyMode TEXT NOT NULL,
                   endMode TEXT NOT NULL,
                   untilDate TEXT,
                   endCount INTEGER,
                   noteText TEXT NOT NULL,
                   noteWidth REAL NOT NULL,
                   noteHeight REAL NOT NULL,
                   createdAt INTEGER NOT NULL,
                   updatedAt INTEGER NOT NULL);""",
        "CREATE INDEX event_span ON event(startDate, endDate);",
        "CREATE INDEX event_recurring ON event(recurring);",
        """CREATE TABLE event_weekday (
                   eventId TEXT NOT NULL REFERENCES event(id) ON DELETE CASCADE,
                   weekday INTEGER NOT NULL,
                   PRIMARY KEY(eventId, weekday));""",
        """CREATE TABLE event_exception (
                   eventId TEXT NOT NULL REFERENCES event(id) ON DELETE CASCADE,
                   date TEXT NOT NULL,
                   PRIMARY KEY(eventId, date));""",
        """CREATE TABLE event_reminder (
                   eventId TEXT NOT NULL REFERENCES event(id) ON DELETE CASCADE,
                   amount INTEGER NOT NULL,
                   unit TEXT NOT NULL,
                   PRIMARY KEY(eventId, amount, unit));""",
        """CREATE TABLE note_stroke (
                   id TEXT PRIMARY KEY,
                   eventId TEXT NOT NULL REFERENCES event(id) ON DELETE CASCADE,
                   "order" INTEGER NOT NULL,
                   color INTEGER NOT NULL,
                   width REAL NOT NULL,
                   style TEXT NOT NULL,
                   blob BLOB NOT NULL);""",
        """CREATE INDEX note_stroke_event_order ON note_stroke(eventId, "order");""",
    )

    /** The current version: [V1]'s step, untouched, then the events step. */
    val V2: StoreSchema = StoreSchema(
        version = 2,
        steps = V1.steps + listOf(EVENTS_STEP),
    )
}
