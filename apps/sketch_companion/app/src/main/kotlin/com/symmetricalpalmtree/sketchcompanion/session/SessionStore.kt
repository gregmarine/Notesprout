package com.symmetricalpalmtree.sketchcompanion.session

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/** `filesDir/session.json`, written atomically (temp + rename) on a store-owned IO scope so no
 *  Activity teardown can cancel a save in flight; saves are debounced, `flushNow` is immediate. */
class SessionStore(context: Context) {
    private val file = File(context.filesDir, "session.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var pending: Session? = null
    private var job: Job? = null

    suspend fun load(): Session = withContext(Dispatchers.IO) {
        try {
            if (file.exists()) json.decodeFromString(Session.serializer(), file.readText()).sanitized() else Session()
        } catch (e: Exception) {
            Log.w(TAG, "session unreadable, starting fresh", e)
            Session()
        }
    }

    @Synchronized
    fun save(session: Session) {
        pending = session
        job?.cancel()
        job = scope.launch { delay(DEBOUNCE_MS); write() }
    }

    @Synchronized
    fun flushNow() {
        job?.cancel()
        job = scope.launch { withContext(NonCancellable) { write() } }
    }

    private fun write() {
        val s = pending ?: return
        try {
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(json.encodeToString(Session.serializer(), s))
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        } catch (e: Exception) {
            Log.w(TAG, "session save failed", e)
        }
    }

    companion object {
        private const val TAG = "SketchCompanion"
        private const val DEBOUNCE_MS = 250L
    }
}
