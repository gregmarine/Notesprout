package com.symmetricalpalmtree.notesproutsn.ext.drive

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

/**
 * The CLOUD_STORAGE point (arc 25 / V1) — scaffold only. Every method: `HostCallerCheck.enforce`
 * first, exactly the tag manager's and calendar's rule.
 *
 * **Store-taking, bind-per-call** (`DRIVE_PLAN.md` § "the seam"): the store rides every call, minted
 * per bind, uid-bound. There is no held bind and no session — a Binder call cannot be cancelled, so
 * the host times every operation itself (`CloudTimeouts`, host-side).
 *
 * V1 has no network code at all — `DriveApi` and `DriveAuth` land in V2. `status` answers what the
 * store already knows (configured from `BuildConfig`, connected from a stored token) without ever
 * touching the network, exactly as [CloudStatus]'s contract requires. Every file operation
 * (`list` / `ensureFolder` / `upload` / `download` / `delete`) validates its arguments against
 * [CloudContract] — the same checks the host itself runs — closes any fd it was handed, and then
 * refuses with `IllegalStateException(CloudContract.NOT_CONNECTED)`: there is no account to reach
 * yet, whether or not one is technically connected, because there is no REST core to reach it with.
 *
 * The account label is user content: **never logged on either side**. Only `SecurityException`,
 * `IllegalArgumentException` and `IllegalStateException` may leave a stub method.
 */
class DriveService : Service() {

    private val binder = object : ICloudStorage.Stub() {

        override fun status(store: IExtensionStore?): CloudStatus {
            enforce()
            requireNotNull(store) { "store is null" }
            val configured = BuildConfig.DRIVE_CLIENT_ID.isNotBlank() && BuildConfig.DRIVE_CLIENT_SECRET.isNotBlank()
            val drive = DriveStore(store)
            val token = try {
                drive.value(DriveSql.Keys.REFRESH_TOKEN)
            } catch (e: StoreUnavailable) {
                throw IllegalStateException(STORE_UNAVAILABLE)
            }
            val label = try {
                drive.value(DriveSql.Keys.ACCOUNT_LABEL)
            } catch (e: StoreUnavailable) {
                throw IllegalStateException(STORE_UNAVAILABLE)
            }
            val connected = configured && !token.isNullOrBlank()
            // Never the label or the token — only shape.
            Slog.d(TAG) { "status: configured=$configured connected=$connected" }
            return CloudStatus(
                connected = connected,
                configured = configured,
                accountLabel = if (connected) (label ?: "") else "",
                providerName = PROVIDER_NAME,
            )
        }

        override fun disconnect(store: IExtensionStore?) {
            enforce()
            requireNotNull(store) { "store is null" }
            // The revoke call with Google is V2's — this only forgets the token locally, which is
            // still a correct (if incomplete) disconnect: the host never sees this account again
            // until Connect runs.
            try {
                DriveStore(store).clear()
            } catch (e: StoreUnavailable) {
                throw IllegalStateException(STORE_UNAVAILABLE)
            }
            Slog.d(TAG) { "disconnect" }
        }

        override fun list(store: IExtensionStore?, path: Array<String>?): Array<CloudEntry> {
            enforce()
            requireNotNull(store) { "store is null" }
            CloudContract.requireValidPath(path)
            throw IllegalStateException(CloudContract.NOT_CONNECTED)
        }

        override fun ensureFolder(store: IExtensionStore?, path: Array<String>?): CloudEntry {
            enforce()
            requireNotNull(store) { "store is null" }
            CloudContract.requireValidPath(path)
            throw IllegalStateException(CloudContract.NOT_CONNECTED)
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
                enforce()
                requireNotNull(store) { "store is null" }
                CloudContract.requireValidPath(path)
                requireNotNull(name) { "name is null" }
                require(CloudContract.isName(name)) { "name is not a name" }
                requireNotNull(mime) { "mime is null" }
                require(CloudContract.isMime(mime)) { "mime is not a mime type" }
                requireNotNull(source) { "source is null" }
                require(expectedBytes >= 0) { "expectedBytes is negative ($expectedBytes)" }
                throw IllegalStateException(CloudContract.NOT_CONNECTED)
            } finally {
                runCatching { source?.close() }
            }
        }

        override fun download(store: IExtensionStore?, entryId: String?, destination: ParcelFileDescriptor?): Long {
            try {
                enforce()
                requireNotNull(store) { "store is null" }
                requireNotNull(entryId) { "entryId is null" }
                require(CloudContract.isEntryId(entryId)) { "entryId is not an id" }
                requireNotNull(destination) { "destination is null" }
                throw IllegalStateException(CloudContract.NOT_CONNECTED)
            } finally {
                runCatching { destination?.close() }
            }
        }

        override fun delete(store: IExtensionStore?, entryId: String?) {
            enforce()
            requireNotNull(store) { "store is null" }
            requireNotNull(entryId) { "entryId is null" }
            require(CloudContract.isEntryId(entryId)) { "entryId is not an id" }
            throw IllegalStateException(CloudContract.NOT_CONNECTED)
        }

        private fun enforce() = HostCallerCheck.enforce(this@DriveService, BuildConfig.HOST_PACKAGE)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    companion object {
        private const val TAG = "DriveService"

        /** What the host's Destination / Source rows and its Cloud section print. */
        const val PROVIDER_NAME = "Google Drive"

        /** The store binder is gone, or could not be reached at all. */
        const val STORE_UNAVAILABLE = "store unavailable"
    }
}
