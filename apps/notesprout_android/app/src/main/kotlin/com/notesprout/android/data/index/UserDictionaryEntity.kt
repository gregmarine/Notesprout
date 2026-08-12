package com.notesprout.android.data.index

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One word the user added to the proofread dictionary ("Add to dictionary" in the document
 * editor's spell popup). A user's vocabulary belongs to them, not to any one document, so this
 * lives in the global index (`notesprout.db`, encrypted at rest), never in a `.soil` file — and
 * backup needs nothing new, because the index file is already covered.
 *
 * The word is the primary key and the whole payload, stored in the spell engine's normal form
 * (lowercase, typographic apostrophe folded to plain — see
 * [com.notesprout.android.core.proofread.SpellEngine.normalizeWord]), so membership is a set
 * lookup with no case games. Removal is a hard DELETE, unlike content objects: a removed word must
 * stop vouching for itself immediately, re-adding is one tap, and with the word as the key there
 * is no identity a tombstone could preserve.
 */
@Entity(tableName = "user_dictionary")
data class UserDictionaryEntity(
    @PrimaryKey val word: String,
    val addedAt: Long,
)
