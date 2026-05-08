package com.notesprout.notesprout.data

import android.database.sqlite.SQLiteCantOpenDatabaseException
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class RegistryEntry(
    val id: String,
    val folderPath: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long
)

data class ReconcileResult(
    val entries: List<RegistryEntry>,
    val orphansAdopted: Int,
    val missingRemoved: Int,
    val skippedCount: Int
)

class NotebookRegistry(private val registryFile: File) {

    companion object {
        private const val TAG = "NotebookRegistry"
        private const val SOIL_FILE = "notebook.soil"
    }

    fun load(): List<RegistryEntry> {
        if (!registryFile.exists()) return emptyList()
        return try {
            val arr = JSONArray(registryFile.readText())
            val entries = mutableListOf<RegistryEntry>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                entries.add(
                    RegistryEntry(
                        id = obj.getString("id"),
                        folderPath = obj.getString("folderPath"),
                        name = obj.getString("name"),
                        createdAt = obj.getLong("createdAt"),
                        updatedAt = obj.getLong("updatedAt")
                    )
                )
            }
            Log.i(TAG, "load: read ${entries.size} entries from ${registryFile.path}")
            entries
        } catch (e: Exception) {
            Log.e(TAG, "load: parse error, returning empty list", e)
            emptyList()
        }
    }

    fun save(entries: List<RegistryEntry>) {
        val arr = JSONArray()
        entries.forEach { e ->
            arr.put(
                JSONObject().apply {
                    put("id", e.id)
                    put("folderPath", e.folderPath)
                    put("name", e.name)
                    put("createdAt", e.createdAt)
                    put("updatedAt", e.updatedAt)
                }
            )
        }
        registryFile.parentFile?.mkdirs()
        registryFile.writeText(arr.toString(2))
        Log.i(TAG, "save: wrote ${entries.size} entries to ${registryFile.path}")
    }

    fun add(entry: RegistryEntry) {
        val entries = load().toMutableList()
        entries.removeAll { it.folderPath == entry.folderPath }
        entries.add(entry)
        save(entries)
        Log.i(TAG, "add: registered '${entry.name}' at ${entry.folderPath}")
    }

    fun remove(folderPath: String) {
        val entries = load().toMutableList()
        val before = entries.size
        entries.removeAll { it.folderPath == folderPath }
        save(entries)
        Log.i(TAG, "remove: removed ${before - entries.size} entry for $folderPath")
    }

    fun update(folderPath: String, name: String) {
        val entries = load().toMutableList()
        val idx = entries.indexOfFirst { it.folderPath == folderPath }
        if (idx >= 0) {
            entries[idx] = entries[idx].copy(name = name, updatedAt = System.currentTimeMillis())
            save(entries)
            Log.i(TAG, "update: renamed entry at $folderPath to '$name'")
        } else {
            Log.w(TAG, "update: no entry found for $folderPath")
        }
    }

    fun reconcile(noteSproutDir: File): ReconcileResult {
        val registered = load().toMutableList()
        val registeredPaths = registered.map { it.folderPath }.toSet()
        var orphansAdopted = 0
        var missingRemoved: Int
        var skippedCount = 0

        // Guard: if the directory exists but can't be listed, don't wipe the registry
        val rawListing = if (noteSproutDir.exists()) noteSproutDir.listFiles() else null
        if (rawListing == null && noteSproutDir.exists()) {
            Log.w(TAG, "reconcile: cannot list $noteSproutDir — returning registry as-is to avoid data loss")
            return ReconcileResult(registered.toList(), 0, 0, 0)
        }

        val diskFolders = rawListing
            ?.filter { it.isDirectory && File(it, SOIL_FILE).exists() }
            ?.map { it.absolutePath }
            ?.toSet()
            ?: emptySet()

        // Adopt orphans: on disk but not in registry
        diskFolders.forEach { path ->
            if (path !in registeredPaths) {
                val soilFile = File(path, SOIL_FILE)
                val meta = tryReadMeta(soilFile)
                if (meta != null) {
                    registered.add(
                        RegistryEntry(
                            id = meta.id,
                            folderPath = path,
                            name = meta.name,
                            createdAt = meta.createdAt,
                            updatedAt = meta.updatedAt
                        )
                    )
                    orphansAdopted++
                    Log.i(TAG, "reconcile: adopted orphan at $path")
                } else {
                    skippedCount++
                    Log.w(TAG, "reconcile: skipped unreadable orphan at $path")
                }
            }
        }

        // Remove missing: in registry but folder no longer on disk
        val missing = registered.filter { it.folderPath !in diskFolders }
        missing.forEach { Log.i(TAG, "reconcile: removing missing entry for ${it.folderPath}") }
        registered.removeAll { it.folderPath !in diskFolders }
        missingRemoved = missing.size

        if (orphansAdopted > 0 || missingRemoved > 0) {
            save(registered)
        }

        Log.i(TAG, "reconcile: ${registered.size} healthy, $orphansAdopted adopted, $missingRemoved removed, $skippedCount skipped")
        return ReconcileResult(registered.toList(), orphansAdopted, missingRemoved, skippedCount)
    }

    private fun tryReadMeta(soilFile: File): NotebookMeta? {
        return try {
            val db = SoilDatabase(soilFile.absolutePath)
            db.open()
            val meta = db.getNotebookMeta()
            db.close()
            meta
        } catch (e: SQLiteCantOpenDatabaseException) {
            Log.e(TAG, "tryReadMeta: SQLiteCantOpenDatabaseException for ${soilFile.path}: ${e.message}")
            null
        } catch (e: Exception) {
            Log.w(TAG, "tryReadMeta: could not read ${soilFile.path}", e)
            null
        }
    }
}
