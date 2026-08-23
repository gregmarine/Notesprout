package com.symmetricalpalmtree.notesproutsn.notebook

import android.content.Context
import android.util.Log
import com.symmetricalpalmtree.notesproutsn.crypto.KeySession
import com.symmetricalpalmtree.notesproutsn.data.soil.SoilDatabase
import com.symmetricalpalmtree.notesproutsn.data.soilFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * A **read-only** open of a browsed notebook's `.soil` for the link picker's foreign page grid
 * (arc 6 / K2) — previews and labels of a notebook that is not the open one. The current notebook
 * must never come through here (the picker hides it in both Notebook modes; its file is already
 * open by the session, and one file never has two connections — the family rule).
 *
 * Opened lazily on the first read, through the one [SoilDatabase.open] door (global key from
 * [KeySession], cached-raw-key fast path; **never creates**). Nothing here writes; the picker
 * holds at most one instance at a time (per drilled notebook) and MUST [sealAsync] it when the
 * drill is left — mode switch, a different notebook, or the screen's destroy. The seal runs on a
 * process-scoped IO job under [NonCancellable], because the destroy path's lifecycle scope is
 * already dead when it fires; an unsealed open would strand the connection and its WAL sidecar
 * for the process lifetime (the R6 lesson).
 *
 * A failed open (file gone, no key session, bad file) is remembered and every read answers empty
 * — the picker shows its empty state; the honest "target is gone" moment belongs to the follow
 * (K4), not to browsing.
 */
class ForeignPageSource(context: Context, private val notebookId: String) : PickerPageSource {

    private val app = context.applicationContext
    private val lock = Mutex()
    private var db: SoilDatabase? = null
    private var failed = false
    private var sealed = false

    override suspend fun pages(): List<PickerPage> =
        withDb { PageReads.pages(it.dao(), notebookId) } ?: emptyList()

    override suspend fun content(pageId: String): PageContent? =
        withDb { PageReads.content(it.dao(), pageId) }

    /** Checkpoint + close, fire-and-forget, idempotent — see the class KDoc for why not a
     *  lifecycle scope. Reads after this answer empty. */
    fun sealAsync() {
        sealScope.launch {
            withContext(NonCancellable) {
                lock.withLock {
                    sealed = true
                    db?.seal(soilFile(app, notebookId))   // never throws (its own contract)
                    db = null
                }
            }
        }
    }

    private suspend fun <T> withDb(block: suspend (SoilDatabase) -> T): T? = lock.withLock {
        if (sealed || failed) return@withLock null
        val open = db ?: openLocked() ?: return@withLock null
        try {
            block(open)
        } catch (e: Exception) {
            Log.w(TAG, "foreign read failed for $notebookId", e)
            null
        }
    }

    private suspend fun openLocked(): SoilDatabase? {
        val passphrase = KeySession.get() ?: run { failed = true; return null }
        val file = soilFile(app, notebookId)
        if (!file.exists() || file.length() == 0L) { failed = true; return null }
        return try {
            withContext(Dispatchers.IO) { SoilDatabase.open(app, notebookId, file, passphrase) }
                .also { db = it }
        } catch (e: Exception) {
            Log.w(TAG, "foreign open failed for $notebookId", e)
            failed = true
            null
        }
    }

    private companion object {
        const val TAG = "ForeignPageSource"

        /** Outlives any Activity so a destroy-time seal always completes. */
        val sealScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
