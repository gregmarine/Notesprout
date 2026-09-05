package com.symmetricalpalmtree.notesproutsn.ext.cloud

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.ParcelFileDescriptor
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.extension.CloudContract
import com.symmetricalpalmtree.notesproutsn.extension.CloudEntry
import com.symmetricalpalmtree.notesproutsn.extension.CloudStatus
import com.symmetricalpalmtree.notesproutsn.extension.HostCallerCheck
import com.symmetricalpalmtree.notesproutsn.extension.ICloudStorage
import com.symmetricalpalmtree.notesproutsn.extension.IExtensionStore
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * The CLOUD_STORAGE point (arc 25 / V1 scaffold, V2 real) — every method: `HostCallerCheck.enforce`
 * first, **inside** the `try` whose `finally` closes any fd, exactly the tag manager's and
 * calendar's rule.
 *
 * **Store-taking, bind-per-call** (`DRIVE_PLAN.md` § "the seam"): the store rides every call, minted
 * per bind, uid-bound. There is no held bind and no session for an operation — a Binder call cannot
 * be cancelled, so the host times every operation itself (`CloudTimeouts`, host-side). The one
 * exception is the **connect showing**, whose bracket is `beginConnect` / `endConnect` and whose
 * parked store [ConnectActivity] reads out of [ConnectSession].
 *
 * The stub itself is thin on purpose: check the caller, validate against [CloudContract] (the same
 * checks the host runs — both sides, always), open the streams, and hand off to [DriveOps], which
 * is a plain class and therefore JVM-testable. `status` still never touches the network.
 *
 * **Only `SecurityException` / `IllegalArgumentException` / `IllegalStateException` may leave a
 * stub.** Everything else — a serialization failure, an NPE, an `IOException` that escaped
 * [DriveHttp] — is funnelled through [DriveFailures.marshalable]: a non-marshalable exception kills
 * the transaction silently and the host waits out its whole timeout for nothing (the arc-2 trap).
 *
 * The account label is user content: **never logged on either side.**
 *
 * **Why the class is `CloudService` and the rest of the module is `Drive*`** (the rename of
 * 2026-09-05): this service IS the point, and the point is generic — so it, the module and the APK
 * label are generic too. Everything it delegates to is Google Drive's own OAuth flow and REST v3
 * client, honestly named. A second provider is baked in beside them ([PROVIDER_NAME] and the
 * `opsFor` wiring being the fork), never as a second extension.
 */
class CloudService : Service() {

    private val binder = object : ICloudStorage.Stub() {

        override fun status(store: IExtensionStore?): CloudStatus = answering {
            enforce()
            requireNotNull(store) { "store is null" }
            opsFor(store).status()
        }

        override fun disconnect(store: IExtensionStore?) = answering {
            enforce()
            requireNotNull(store) { "store is null" }
            opsFor(store).disconnect()
        }

        override fun beginConnect(store: IExtensionStore?) = answering {
            enforce()
            requireNotNull(store) { "store is null" }
            synchronized(ConnectSession) {
                ConnectSession.clear()
                ConnectSession.store = store
            }
            Slog.d(TAG) { "beginConnect" }
        }

        override fun endConnect() = answering {
            enforce()
            ConnectSession.clear()
            Slog.d(TAG) { "endConnect" }
        }

        override fun list(store: IExtensionStore?, path: Array<String>?): Array<CloudEntry> = answering {
            enforce()
            requireNotNull(store) { "store is null" }
            val validPath = CloudContract.requireValidPath(path)
            opsFor(store).list(validPath)
        }

        override fun ensureFolder(store: IExtensionStore?, path: Array<String>?): CloudEntry = answering {
            enforce()
            requireNotNull(store) { "store is null" }
            val validPath = CloudContract.requireValidPath(path)
            opsFor(store).ensureFolder(validPath)
        }

        override fun upload(
            store: IExtensionStore?,
            path: Array<String>?,
            name: String?,
            mime: String?,
            source: ParcelFileDescriptor?,
            expectedBytes: Long,
        ): CloudEntry {
            try {
                return answering {
                    enforce()
                    requireNotNull(store) { "store is null" }
                    val validPath = CloudContract.requireValidPath(path)
                    requireNotNull(name) { "name is null" }
                    require(CloudContract.isName(name)) { "name is not a name" }
                    requireNotNull(mime) { "mime is null" }
                    require(CloudContract.isMime(mime)) { "mime is not a mime type" }
                    requireNotNull(source) { "source is null" }
                    require(expectedBytes >= 0) { "expectedBytes is negative ($expectedBytes)" }
                    FileInputStream(source.fileDescriptor).use { input ->
                        opsFor(store).upload(validPath, name, mime, input, expectedBytes)
                    }
                }
            } finally {
                runCatching { source?.close() }
            }
        }

        override fun download(store: IExtensionStore?, entryId: String?, destination: ParcelFileDescriptor?): Long {
            try {
                return answering {
                    enforce()
                    requireNotNull(store) { "store is null" }
                    requireNotNull(entryId) { "entryId is null" }
                    require(CloudContract.isEntryId(entryId)) { "entryId is not an id" }
                    requireNotNull(destination) { "destination is null" }
                    FileOutputStream(destination.fileDescriptor).use { out ->
                        // Truncate first (the seam says so) — best effort, because a destination
                        // that is not a seekable file has nothing to truncate.
                        runCatching { out.channel.truncate(0L) }
                        val written = opsFor(store).download(entryId, out)
                        out.flush()
                        // Durability is the seam's promise, not the host's: the bytes are on the
                        // platter before the count crosses back.
                        out.fd.sync()
                        written
                    }
                }
            } finally {
                runCatching { destination?.close() }
            }
        }

        override fun delete(store: IExtensionStore?, entryId: String?) = answering {
            enforce()
            requireNotNull(store) { "store is null" }
            requireNotNull(entryId) { "entryId is null" }
            require(CloudContract.isEntryId(entryId)) { "entryId is not an id" }
            opsFor(store).delete(entryId)
        }

        private fun enforce() = HostCallerCheck.enforce(this@CloudService, BuildConfig.HOST_PACKAGE)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    /**
     * Everything a call needs, built fresh per call — the store binder is only good for this call
     * anyway, and the one thing worth keeping between calls (the access token) lives in the
     * process-wide [DriveTokens] cache rather than here.
     */
    private fun opsFor(store: IExtensionStore): DriveOps {
        val driveStore = DriveStore(store)
        val tokens = TokenSource(
            store = driveStore,
            cache = DriveTokens.cache,
            transport = DriveHttp,
            clientId = BuildConfig.DRIVE_CLIENT_ID,
            clientSecret = BuildConfig.DRIVE_CLIENT_SECRET,
        )
        val api = DriveApi(DriveHttp, tokens, driveStore, BuildConfig.ROOT_FOLDER_NAME)
        return DriveOps(driveStore, api, tokens, PROVIDER_NAME, configured())
    }

    /** The last gate before a stub returns — see the class note. */
    private fun <T> answering(block: () -> T): T =
        try {
            block()
        } catch (e: Throwable) {
            throw DriveFailures.marshalable(e)
        }

    companion object {
        private const val TAG = "CloudService"

        /** What the host's Destination / Source rows and its Cloud section print. */
        const val PROVIDER_NAME = "Google Drive"

        /** The store binder is gone, or could not be reached at all. */
        const val STORE_UNAVAILABLE = "store unavailable"

        /** Whether this APK was built with its OAuth client at all (blank env vars → false, and the
         *  host dialogs rather than offering a Connect that cannot work). */
        fun configured(): Boolean =
            BuildConfig.DRIVE_CLIENT_ID.isNotBlank() && BuildConfig.DRIVE_CLIENT_SECRET.isNotBlank()
    }
}
