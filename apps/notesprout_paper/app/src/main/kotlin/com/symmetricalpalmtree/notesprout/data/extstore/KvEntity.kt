package com.symmetricalpalmtree.notesprout.data.extstore

import androidx.room.Entity
import androidx.room.PrimaryKey

/** One row of an extension store: `kv(key TEXT PRIMARY KEY, value BLOB NOT NULL, updatedAt INTEGER NOT NULL)`. */
@Entity(tableName = "kv")
class KvEntity(
    @PrimaryKey val key: String,
    val value: ByteArray,
    val updatedAt: Long,
)
