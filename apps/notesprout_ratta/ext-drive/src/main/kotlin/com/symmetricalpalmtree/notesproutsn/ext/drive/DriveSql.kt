package com.symmetricalpalmtree.notesproutsn.ext.drive

import com.symmetricalpalmtree.notesproutsn.extension.Statement

/**
 * Every statement the Drive provider sends against its `account` table (arc 25 / V1), as pure
 * builders — SQL text and bound arguments and nothing else, so the shapes are JVM-testable without
 * a store.
 *
 * `account` has no children and no cascade to protect, so a plain `INSERT OR REPLACE` is safe here
 * — unlike the tag manager's tables, there is nothing a REPLACE's delete-then-insert could take
 * down with it.
 */
object DriveSql {

    /** The keys this provider stores under — a handful of rows, not a wider table. */
    object Keys {
        const val REFRESH_TOKEN: String = "refreshToken"
        const val ACCOUNT_LABEL: String = "accountLabel"
        const val ROOT_FOLDER_ID: String = "rootFolderId"
    }

    /** Read one value by key; absent is a real answer (no row), not a failure. */
    fun selectValue(key: String): Statement =
        Statement("SELECT value FROM account WHERE key = ?", key)

    /** Write (or overwrite) one value by key. Safe as REPLACE: `account` has no children. */
    fun upsertValue(key: String, value: String): Statement =
        Statement("INSERT OR REPLACE INTO account (key, value) VALUES (?, ?)", key, value)

    /** Forget one key — a no-op if it was never set. */
    fun deleteValue(key: String): Statement =
        Statement("DELETE FROM account WHERE key = ?", key)

    /** Forget the whole account — `disconnect`'s statement. */
    fun deleteAll(): Statement =
        Statement("DELETE FROM account")
}
