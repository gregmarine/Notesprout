package com.symmetricalpalmtree.notesproutsn.ext.bible

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.extension.HostCallerCheck
import com.symmetricalpalmtree.notesproutsn.extension.IBible
import com.symmetricalpalmtree.notesproutsn.extension.IExtensionStore

/**
 * The BIBLE point (arc 37 / B0) — SN's NINTH capability point and the fifth screen-owning one.
 * The tag manager's showing bracket and nothing more, minus `configureShowing`: the host
 * pre-opens the store, holds the bind, calls `begin(store)`, launches [BibleActivity] for a
 * result, and calls `end()` when the screen has returned.
 *
 * **Scripture never crosses this seam.** The extension reads whatever text it shows from its own
 * assets or its own bundled data — never from the host, never over this Binder. The store carries
 * only the reader's one row of per-device state (the last-read position), and [BibleStore] never
 * logs its value.
 *
 * **Logs are structure only**: `begin` / `end`, never the position, never a scripture reference.
 */
class BibleService : Service() {

    private val binder = object : IBible.Stub() {

        override fun begin(store: IExtensionStore?) {
            HostCallerCheck.enforce(this@BibleService, BuildConfig.HOST_PACKAGE)
            requireNotNull(store) { "store is null" }
            synchronized(BibleSession) {
                BibleSession.clear()
                BibleSession.store = store
            }
            Slog.d(TAG) { "begin" }
        }

        override fun end() {
            HostCallerCheck.enforce(this@BibleService, BuildConfig.HOST_PACKAGE)
            synchronized(BibleSession) { BibleSession.clear() }
            Slog.d(TAG) { "end" }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private companion object {
        const val TAG = "BibleService"
    }
}
