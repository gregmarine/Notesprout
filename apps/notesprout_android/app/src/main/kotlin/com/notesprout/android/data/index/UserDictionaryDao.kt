package com.notesprout.android.data.index

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/** The proofread user dictionary — add, remove, and the full word list. */
@Dao
interface UserDictionaryDao {

    /** Every saved word, alphabetical — the whole table is a few hundred rows at most. */
    @Query("SELECT word FROM user_dictionary ORDER BY word")
    suspend fun allWords(): List<String>

    /** REPLACE: adding a word twice (two editors, a re-add after remove) is not an error. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun add(row: UserDictionaryEntity)

    @Query("DELETE FROM user_dictionary WHERE word = :word")
    suspend fun remove(word: String)
}
