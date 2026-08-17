package com.symmetricalpalmtree.notesprout.data.extstore

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/** Blocking DAO over one extension store. Called on the host's Binder thread — never Main. */
@Dao
interface KvDao {
    @Query("SELECT `value` FROM kv WHERE `key` = :key")
    fun get(key: String): ByteArray?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(row: KvEntity)

    @Query("DELETE FROM kv WHERE `key` = :key")
    fun delete(key: String)

    /** [pattern] is a ready `LIKE` pattern with `\` as the escape char — see [ExtensionStoreGate.likePattern]. */
    @Query("SELECT `key` FROM kv WHERE `key` LIKE :pattern ESCAPE '\\' ORDER BY `key`")
    fun keysLike(pattern: String): List<String>

    @Query("SELECT count(*) FROM kv")
    fun count(): Int
}
