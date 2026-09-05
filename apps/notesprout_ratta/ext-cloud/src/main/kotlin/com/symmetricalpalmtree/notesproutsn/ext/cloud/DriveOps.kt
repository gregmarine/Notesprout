package com.symmetricalpalmtree.notesproutsn.ext.cloud

import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.extension.CloudEntry
import com.symmetricalpalmtree.notesproutsn.extension.CloudStatus
import java.io.InputStream
import java.io.OutputStream

/**
 * **What [CloudService] actually does, as a plain class** (arc 25 / V2).
 *
 * A `Binder.Stub` cannot be instantiated on the JVM, so a seam whose behaviour lives inside the stub
 * is a seam that can only be tested on a device. Everything above argument validation and fd
 * handling therefore lives here: the service checks its caller, validates, opens the streams, and
 * delegates. This class is the unit the JVM tests exercise over a fake store and a fake transport —
 * which is the whole gate this arc has, since there is no code review (`DRIVE_PLAN.md` decision 12).
 *
 * It touches no Android type beyond the two wire parcelables, and it never logs the account.
 */
class DriveOps(
    private val store: DriveStore,
    private val api: DriveApi,
    private val tokens: TokenSource,
    private val providerName: String,
    private val configured: Boolean,
) {

    /**
     * What the store says — **never the network** ([CloudStatus]'s contract). A token that was
     * revoked server-side still reads as connected here; it surfaces as `not connected` on the first
     * operation that needs it, and that is by design: `status()` is on the path of every screen the
     * host draws and must not block on a socket.
     */
    fun status(): CloudStatus {
        val token = store.value(DriveSql.Keys.REFRESH_TOKEN)
        val connected = configured && !token.isNullOrBlank()
        val label = if (connected) DriveJson.label(store.value(DriveSql.Keys.ACCOUNT_LABEL) ?: "") else ""
        // Shape only — never the label, never the token.
        Slog.d(TAG) { "status: configured=$configured connected=$connected" }
        return CloudStatus(
            connected = connected,
            configured = configured,
            accountLabel = label,
            providerName = providerName,
        )
    }

    /**
     * Revoke with Google (best effort, bounded by the http timeouts) and then forget everything
     * locally. **A failed revoke is never an error**: the person asked to be disconnected, and the
     * local forget is what makes that true on this device. Idempotent — not connected is fine here.
     */
    fun disconnect() {
        tokens.revoke()
        store.clear()
        tokens.invalidate()
        Slog.d(TAG) { "disconnect: account forgotten" }
    }

    fun list(path: Array<String>): Array<CloudEntry> = api.list(path).toTypedArray()

    fun ensureFolder(path: Array<String>): CloudEntry = api.ensurePath(path)

    fun upload(
        path: Array<String>,
        name: String,
        mime: String,
        source: InputStream,
        expectedBytes: Long,
    ): CloudEntry = api.upload(path, name, mime, source, expectedBytes)

    fun download(entryId: String, sink: OutputStream): Long = api.download(entryId, sink)

    fun delete(entryId: String) = api.delete(entryId)

    private companion object {
        const val TAG = "DriveOps"
    }
}
