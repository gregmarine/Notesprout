package com.notesprout.android.recognition

import android.util.Log
import com.notesprout.android.core.Slog
import com.notesprout.android.data.SoilDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Real-time recognition scheduler: keeps each page's `page_text` cache fresh as the user writes,
 * without ever touching the UI thread or competing with active inking.
 *
 * - **Idle-debounced, not per-stroke.** [noteEdit] restarts a ~2 s timer per page; only the last
 *   edit in a burst triggers recognition. Rapid writing coalesces into one job.
 * - **Conflated + serialized.** A newer edit cancels the pending job for that page; a [Mutex]
 *   ensures two jobs never run at once (and never race [SoilDatabase] writes with each other).
 * - **Off the UI thread.** Everything runs on [scope] (the app IO scope).
 *
 * The scheduler reads the live DB through [dbProvider] so a job that fires after the notebook DB
 * was closed simply no-ops. See docs/handwriting-recognition.md § "Path 1 — RTR".
 */
class RtrScheduler(
    private val scope: CoroutineScope,
    private val recognizer: PageTextRecognizer,
    private val dbProvider: () -> SoilDatabase?,
) {
    private val pending = ConcurrentHashMap<String, Job>()
    private val mutex = Mutex()

    /** Note that [pageId] was edited; (re)start its debounce timer. */
    fun noteEdit(pageId: String) {
        if (pageId.isEmpty()) return
        pending.remove(pageId)?.cancel()
        pending[pageId] = scope.launch {
            delay(DEBOUNCE_MS)
            runPage(pageId)
            pending.remove(pageId)
        }
    }

    /** Recognize [pageId] immediately (page-seal boundary) — bypasses the debounce. */
    fun flush(pageId: String) {
        if (pageId.isEmpty()) return
        pending.remove(pageId)?.cancel()
        scope.launch { runPage(pageId) }
    }

    /** Cancel all pending debounce timers (call on pause / before sealing / closing the DB). */
    fun cancelAll() {
        pending.values.forEach { it.cancel() }
        pending.clear()
    }

    /**
     * Backfill every page in [pageIds] that is missing or stale (turning RTR on for an existing or
     * imported notebook). [onProgress] is invoked on the IO scope with (done, total).
     */
    suspend fun backfill(pageIds: List<String>, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }) {
        val total = pageIds.size
        for ((i, pageId) in pageIds.withIndex()) {
            mutex.withLock {
                val db = dbProvider() ?: return
                runCatching {
                    val dao = db.notebookDao()
                    val max = PageTextRepository.layerMaxUpdatedAt(dao, pageId)
                    val cached = PageTextRepository.getCached(dao, pageId)
                    if (!PageTextRepository.isFresh(cached, max)) {
                        PageTextRepository.recognizeAndCache(dao, pageId, recognizer)
                    }
                }.onFailure { Log.e(TAG, "Backfill failed for $pageId", it) }
            }
            onProgress(i + 1, total)
        }
    }

    private suspend fun runPage(pageId: String) {
        mutex.withLock {
            val db = dbProvider() ?: return
            runCatching {
                PageTextRepository.recognizeAndCache(db.notebookDao(), pageId, recognizer)
            }.onFailure { Log.e(TAG, "RTR recognition failed for $pageId", it) }
        }
        Slog.d(TAG) { "RTR job complete for $pageId" }
    }

    companion object {
        private const val TAG = "RtrScheduler"
        /** Pen-inactivity window before a page is recognized. */
        private const val DEBOUNCE_MS = 2000L
    }
}
