package com.symmetricalpalmtree.notesproutsn.ext.calendar

import com.symmetricalpalmtree.gpaper.core.model.Stroke
import com.symmetricalpalmtree.notesproutsn.extension.Statement

/**
 * What the event note contributes to one save (arc 24 / Z3): the stroke statements that ride the
 * event's transaction, and the stroke ids this save **minted** — what a failed multi-batch write on
 * an *existing* event gives back, one `DELETE` each (the calendar's placement rule; a new event's
 * compensation is its row's delete and the cascade).
 *
 * Which of the two shapes a save takes is the store's answer, not the screen's: [EventStore.edit]
 * decides the id the edited fields land under and asks for the note **for that id**. In place
 * (the id the note was loaded for) the note's pending op log is the write; under a fresh id — a
 * *this occurrence* override or a new *following* series — the whole note is **copied** with fresh
 * stroke ids ([copy]), because `note_stroke.id` is the primary key and a re-parented row would
 * steal the original series' note rather than copy it.
 */
class NoteWrite(val statements: List<Statement>, val mintedStrokeIds: List<String>) {

    companion object {
        /** No note at all — a fields-only save. */
        val NONE = NoteWrite(emptyList(), emptyList())

        /**
         * The whole note — [entries], `(order, stroke)` in writing order — put under [eventId] with
         * a fresh id from [mintId] for every stroke, orders kept. No minted list: the copy lands
         * under an id this save created, whose compensation is the row's own delete.
         */
        fun copy(entries: List<Pair<Long, Stroke>>, eventId: String, mintId: () -> String): NoteWrite =
            NoteWrite(entries.map { (order, s) -> NoteSql.putStroke(eventId, order, s.copy(id = mintId())) }, emptyList())
    }
}
