package com.symmetricalpalmtree.notesproutsn.data.extstore

/**
 * The store **file** format's version ladder (arc 22 / X1), riding `PRAGMA user_version` — pure,
 * so the decision is a JVM-tested table and the open helper's callbacks only act on it.
 *
 * - `0` on an empty file → [Decision.FRESH]: create `host_schema`.
 * - `1` (the Room-era key/value store) or **any** `kv` / `room_master_table` in `sqlite_master`
 *   → [Decision.WIPE]: drop those tables, create `host_schema`, stamp [VERSION]. **No migration**
 *   (the arc-22 wizard's call — `0.1.0-ratta` is unreleased and the data is test data); the wipe is
 *   logged as a row count, never a key.
 * - `2` = [VERSION], the table store → [Decision.OPEN].
 * - above [VERSION] → [Decision.REFUSE]: a newer host wrote it. Never-delete-on-corruption applies —
 *   the file is left exactly as found and the extension is "unavailable".
 *
 * The extension's own schema version is a different number in a different place (`host_schema`),
 * applied per `StoreSchema` by the gate — this ladder is only about what the host wrote.
 */
object StoreFormat {

    /** The table-store format. */
    const val VERSION = 2

    /** The Room-era format's version (the one [Decision.WIPE] mainly meets). */
    const val LEGACY_KV_VERSION = 1

    /** The host's one table: the applied extension-schema version, in a single row `id = 0`. */
    const val HOST_SCHEMA_TABLE = "host_schema"
    const val CREATE_HOST_SCHEMA =
        "CREATE TABLE IF NOT EXISTS $HOST_SCHEMA_TABLE (id INTEGER PRIMARY KEY CHECK (id = 0), version INTEGER NOT NULL)"
    const val SEED_HOST_SCHEMA = "INSERT OR IGNORE INTO $HOST_SCHEMA_TABLE (id, version) VALUES (0, 0)"

    /** The Room-era tables a wipe removes. */
    val LEGACY_TABLES: List<String> = listOf("kv", "room_master_table")

    enum class Decision { FRESH, WIPE, OPEN, REFUSE }

    fun decide(userVersion: Int, hasLegacyTables: Boolean): Decision = when {
        userVersion > VERSION -> Decision.REFUSE
        userVersion == VERSION && !hasLegacyTables -> Decision.OPEN
        userVersion == 0 && !hasLegacyTables -> Decision.FRESH
        else -> Decision.WIPE
    }
}
