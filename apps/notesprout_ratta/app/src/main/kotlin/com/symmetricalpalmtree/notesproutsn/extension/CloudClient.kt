package com.symmetricalpalmtree.notesproutsn.extension

import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.data.extstore.ExtensionStoreBinder
import com.symmetricalpalmtree.notesproutsn.data.extstore.ExtensionStores
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * No account is connected (never was, `disconnect` ran, or the provider's token was revoked out from
 * under it). The host's answer is to **offer Connect**. Nothing was changed.
 */
class CloudNotConnectedException(cause: Throwable) :
    ExtensionCallException(CloudContract.NOT_CONNECTED, cause)

/**
 * The provider could not reach its service — offline, DNS, TLS, a 5xx, a timeout of its own. The
 * host's answer is "**nothing changed, try again**". Distinct from a plain [ExtensionCallException],
 * which means the provider did not answer at all and says nothing about what did or did not land.
 */
class CloudNetworkException(cause: Throwable) :
    ExtensionCallException(CloudContract.NETWORK, cause)

/**
 * The host's client for the one cloud provider (arc 25 / V1) — SN's **eighth** extension point.
 *
 * **Store-taking, bind-per-call** (the tag manager's second call shape, [TagClient.search]'s half):
 * every operation is one bind, one call, one unbind. The store is opened on IO **before** the bind —
 * a cold SQLCipher KDF is seconds on the Nomad and must never sit inside a call timeout — minted as
 * a uid-bound [ExtensionStoreBinder], handed to the call as an argument, and `revoke`d in `finally`
 * whatever happened. There is no held bind and no session, because nothing here is a *showing*: an
 * upload is an operation, not a screen.
 *
 * Each call runs under its own budget from [CloudTimeouts], measured on the device rather than
 * chosen — a Binder call cannot be cancelled, so a budget that runs out leaves the provider still
 * working while the host has already spoken. Nothing here is retried automatically for the same
 * reason: only the person knows whether "try again" is safe, and every one of these operations is
 * replace-by-name or idempotent so that saying yes is cheap.
 *
 * **What crosses:** folder *names*, an opaque entry id, a MIME type, and file descriptors. **No
 * secret, no device path, no URL** — in either direction. The account label comes back inside
 * [CloudStatus] and is **user content: never logged**; the log lines below carry booleans, counts
 * and durations only.
 *
 * **The two typed refusals**, compared **verbatim** (never as a substring — the family rule) against
 * the `IllegalStateException` message the stub threw:
 *  - [CloudContract.NOT_CONNECTED] → [CloudNotConnectedException]; the host offers **Connect**.
 *  - [CloudContract.NETWORK] → [CloudNetworkException]; the host says **nothing changed** and offers
 *    to try again.
 *
 * Any other failure — bind refused, timeout, `RemoteException`, a reply that failed validation — is
 * the plain [ExtensionCallException] every other client throws, and reads as "the provider didn't
 * answer".
 *
 * **Arguments are checked here, before the bind** ([CloudArgs]) — a refusal must never cost a
 * bind, because binding starts a process. Replies are checked on the way back for the shape the
 * contract promises; the parcelables' own constructors have already checked their fields.
 *
 * **File descriptors.** [upload] and [download] each take one the caller opened, and **this client
 * owns it from that moment**: the Binder transaction dupes it into the provider's process (whose
 * stub closes its copy in its own `finally`) and the host's end is closed here in `finally`, on
 * every path including a refusal that never reached a bind. A caller must not use the descriptor
 * afterwards.
 *
 * The connect *showing* is not here — it is a held bind with a bracket, and it lives in
 * [CloudConnectClient].
 */
object CloudClient {

    const val TAG = "CloudClient"

    /**
     * What the provider's store says about the account: is the extension configured at all, is an
     * account connected, and what the person calls it. **Never touches the network** (the contract
     * forbids it), so it is the one call cheap enough to make on a screen's resume.
     *
     * @throws CloudNotConnectedException the provider refused with [CloudContract.NOT_CONNECTED].
     * @throws CloudNetworkException the provider refused with [CloudContract.NETWORK].
     * @throws ExtensionCallException the store, the bind, the call or the reply failed.
     */
    suspend fun status(context: Context, ref: ProviderRef): CloudStatus {
        val appContext = context.applicationContext
        val store = openStore(appContext, ref) ?: throw ExtensionCallException("store unavailable")
        val t0 = System.currentTimeMillis()
        try {
            val status = ExtensionBinder.call(
                appContext, ref, CloudContract.ACTION_CLOUD_STORAGE, TAG,
                asInterface = { ICloudStorage.Stub.asInterface(it) },
                callTimeoutMs = CloudTimeouts.STATUS_MS,
            ) { iface ->
                mapRefusals { iface.status(store) } ?: throw ExtensionCallException("status returned nothing")
            }
            // Booleans and a duration. The account label is the person's own and is never logged.
            Slog.d(TAG) {
                "status: configured=${status.configured} connected=${status.connected} " +
                    "in ${System.currentTimeMillis() - t0} ms"
            }
            return status
        } finally {
            store.revoke()
        }
    }

    /**
     * Forget the connected account: the provider revokes its token with its service (best effort,
     * bounded on its side) and clears it from the store. **Idempotent** — disconnecting something
     * that is not connected is not an error, so this never throws [CloudNotConnectedException].
     *
     * A [CloudNetworkException] here is **not a failure to report as one**: the provider forgets the
     * token locally whichever way the revoke went, so the account is disconnected on this device
     * either way. The caller re-renders and moves on.
     *
     * @throws CloudNetworkException the revoke could not reach the provider's service.
     * @throws ExtensionCallException the store, the bind, or the call failed.
     */
    suspend fun disconnect(context: Context, ref: ProviderRef) {
        call(context, ref, "disconnect", CloudTimeouts.DISCONNECT_MS) { iface, store ->
            iface.disconnect(store)
        }
        Slog.d(TAG) { "disconnect: done" }
    }

    /**
     * The folders and files directly under [path] — folders first, then files, each group by name.
     * A folder that does not exist answers an **empty list**, not a failure: to the host a missing
     * folder and an empty one look the same, and `ensureFolder` is how one comes to exist.
     *
     * @throws CloudNotConnectedException no account is connected — offer Connect.
     * @throws CloudNetworkException the provider could not reach its service; nothing changed.
     * @throws ExtensionCallException the arguments, the store, the bind, the call or the reply failed.
     */
    suspend fun list(context: Context, ref: ProviderRef, path: Array<String>): List<CloudEntry> {
        CloudArgs.requirePath(path)
        val t0 = System.currentTimeMillis()
        val entries = call(context, ref, "list", CloudTimeouts.LIST_MS) { iface, store ->
            CloudArgs.checkList(iface.list(store, path))
        }
        // Counts and a duration. Folder names here are the host's own or the user's file naming;
        // neither is logged, because the listing is of the person's own cloud.
        Slog.d(TAG) {
            "list: depth=${path.size} → ${entries.size} entries in ${System.currentTimeMillis() - t0} ms"
        }
        return entries
    }

    /**
     * Find or create every segment of [path] in turn and answer the last one; an empty path answers
     * the provider's root. Nothing is ever created beside an existing name — a same-named sibling is
     * a provider fact (Drive allows them) and the first by the provider's order is the one.
     *
     * @throws CloudNotConnectedException no account is connected — offer Connect.
     * @throws CloudNetworkException the provider could not reach its service; nothing changed.
     * @throws ExtensionCallException the arguments, the store, the bind, the call or the reply failed.
     */
    suspend fun ensureFolder(context: Context, ref: ProviderRef, path: Array<String>): CloudEntry {
        CloudArgs.requirePath(path)
        val t0 = System.currentTimeMillis()
        val entry = call(context, ref, "ensureFolder", CloudTimeouts.ENSURE_FOLDER_MS) { iface, store ->
            CloudArgs.checkFolder(iface.ensureFolder(store, path))
        }
        Slog.d(TAG) { "ensureFolder: depth=${path.size} in ${System.currentTimeMillis() - t0} ms" }
        return entry
    }

    /**
     * Write [source]'s bytes as the file [name] under [path], creating the folders on the way, and
     * answer the entry **as the provider reports it afterwards**.
     *
     * **Replace-by-name**: a file of that name already there is updated in place and keeps its id.
     * [expectedBytes] is what the caller wrote into [source]; the provider streams exactly that many
     * and refuses a short or long read. The returned [CloudEntry.sizeBytes] is *corroboration*, and
     * a disagreement means **check the file** — never delete it (the arc-15 rule, and the standing
     * trap that a provider's metadata can lag its own write). This client does not compare them: it
     * has nowhere to say so.
     *
     * The budget is [CloudTimeouts.uploadBudgetMs] of [expectedBytes] — the one call on this seam
     * whose timeout is computed. **[source] is closed here** whatever happens, including a refusal
     * before the bind.
     *
     * @throws CloudNotConnectedException no account is connected — offer Connect.
     * @throws CloudNetworkException the provider could not reach its service; nothing was written.
     * @throws ExtensionCallException the arguments, the store, the bind, the call or the reply failed.
     */
    suspend fun upload(
        context: Context,
        ref: ProviderRef,
        path: Array<String>,
        name: String,
        mime: String,
        source: ParcelFileDescriptor,
        expectedBytes: Long,
    ): CloudEntry {
        try {
            CloudArgs.requirePath(path)
            CloudArgs.requireName(name)
            CloudArgs.requireMime(mime)
            CloudArgs.requireExpectedBytes(expectedBytes)
            val t0 = System.currentTimeMillis()
            val entry = call(context, ref, "upload", CloudTimeouts.uploadBudgetMs(expectedBytes)) { iface, store ->
                CloudArgs.checkUploaded(iface.upload(store, path, name, mime, source, expectedBytes))
            }
            // Byte counts and a duration; the file's name is the person's own and stays out of it.
            Slog.d(TAG) {
                "upload: $expectedBytes B → ${entry.sizeBytes} B reported, " +
                    "agrees=${entry.sizeBytes == expectedBytes} in ${System.currentTimeMillis() - t0} ms"
            }
            return entry
        } finally {
            // The host's end of the descriptor. The provider's stub closes the dup it received.
            runCatching { source.close() }
        }
    }

    /**
     * Stream the file [entryId] into [destination] (truncated first, fsynced by the provider) and
     * answer the bytes written. **[destination] is closed here** whatever happens.
     *
     * @throws CloudNotConnectedException no account is connected — offer Connect.
     * @throws CloudNetworkException the provider could not reach its service; nothing was read.
     * @throws ExtensionCallException the arguments, the store, the bind, the call or the reply failed.
     */
    suspend fun download(
        context: Context,
        ref: ProviderRef,
        entryId: String,
        destination: ParcelFileDescriptor,
    ): Long {
        try {
            CloudArgs.requireEntryId(entryId)
            val t0 = System.currentTimeMillis()
            val bytes = call(context, ref, "download", CloudTimeouts.DOWNLOAD_MS) { iface, store ->
                CloudArgs.checkDownloaded(iface.download(store, entryId, destination))
            }
            Slog.d(TAG) { "download: $bytes B in ${System.currentTimeMillis() - t0} ms" }
            return bytes
        } finally {
            runCatching { destination.close() }
        }
    }

    /**
     * Delete the file or folder [entryId]. Idempotent on an id that is already gone.
     *
     * @throws CloudNotConnectedException no account is connected — offer Connect.
     * @throws CloudNetworkException the provider could not reach its service; nothing was deleted.
     * @throws ExtensionCallException the arguments, the store, the bind or the call failed.
     */
    suspend fun delete(context: Context, ref: ProviderRef, entryId: String) {
        CloudArgs.requireEntryId(entryId)
        val t0 = System.currentTimeMillis()
        call(context, ref, "delete", CloudTimeouts.DELETE_MS) { iface, store -> iface.delete(store, entryId) }
        Slog.d(TAG) { "delete: done in ${System.currentTimeMillis() - t0} ms" }
    }

    /**
     * The shape every operation above shares, exactly once: pre-open the store on IO, one bind, one
     * call under [timeoutMs] with the refusals mapped, `revoke` in `finally` whatever happened.
     *
     * [op] names the operation in the failure text only — never in a line carrying user content.
     */
    private suspend fun <T> call(
        context: Context,
        ref: ProviderRef,
        op: String,
        timeoutMs: Long,
        block: (ICloudStorage, ExtensionStoreBinder) -> T,
    ): T {
        val appContext = context.applicationContext
        val store = openStore(appContext, ref) ?: throw ExtensionCallException("store unavailable for $op")
        try {
            return ExtensionBinder.call(
                appContext, ref, CloudContract.ACTION_CLOUD_STORAGE, TAG,
                asInterface = { ICloudStorage.Stub.asInterface(it) },
                callTimeoutMs = timeoutMs,
            ) { iface -> mapRefusals { block(iface, store) } }
        } finally {
            store.revoke()
        }
    }

    /**
     * The two typed refusals, lifted out of the stub's `IllegalStateException` by a **verbatim**
     * message match. Everything else is rethrown untouched for [ExtensionBinder.call] to wrap.
     *
     * Runs **inside** the call block, on the IO thread the transaction occupies, so the comparison
     * happens before the exception has been flattened into anything else.
     */
    private inline fun <T> mapRefusals(block: () -> T): T =
        try {
            block()
        } catch (e: IllegalStateException) {
            // Compared verbatim, never as a substring (the family rule).
            when (e.message) {
                CloudContract.NOT_CONNECTED -> throw CloudNotConnectedException(e)
                CloudContract.NETWORK -> throw CloudNetworkException(e)
                else -> throw e
            }
        }

    /**
     * Open the provider's extension store on IO and wrap it in a uid-bound binder, or null (logged).
     * The pre-open rule: this is the seconds-long part, and it happens before the bind so that no
     * call budget has to cover it.
     */
    private suspend fun openStore(appContext: Context, ref: ProviderRef): ExtensionStoreBinder? =
        try {
            val db = withContext(Dispatchers.IO) { ExtensionStores.open(appContext, ref.packageName) }
            val extUid = appContext.packageManager.getPackageUid(ref.packageName, 0)
            ExtensionStoreBinder(db, extUid)
        } catch (e: CancellationException) {
            throw e
        } catch (e: PackageManager.NameNotFoundException) {
            Slog.d(TAG) { "store open failed: package gone ${ref.packageName}" }
            null
        } catch (e: Exception) {
            Slog.d(TAG) { "store open failed: ${e.javaClass.simpleName}: ${e.message}" }
            null
        }
}
