package com.symmetricalpalmtree.notesproutsn.data.extstore

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

    /**
     * Keys starting with [prefix] (`""` = all), ascending. `substr` = exact, case-sensitive
     * character comparison — SQLite `LIKE` is ASCII-case-insensitive per connection *and* reads
     * `%` / `_` as wildcards, either of which would break the "starts with" contract.
     */
    @Query("SELECT `key` FROM kv WHERE substr(`key`, 1, length(:prefix)) = :prefix ORDER BY `key`")
    fun keysWithPrefix(prefix: String): List<String>

    @Query("SELECT count(*) FROM kv")
    fun count(): Int
}
