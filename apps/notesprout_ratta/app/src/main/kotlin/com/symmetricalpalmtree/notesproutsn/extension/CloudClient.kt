package com.symmetricalpalmtree.notesproutsn.extension

import android.content.Context
import android.content.pm.PackageManager
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
 * **V2 adds the rest of [ICloudStorage]**: `disconnect`, `list`, `ensureFolder`, `upload`,
 * `download`, `delete`. Each is the same shape as [status] — pre-open the store, one
 * [ExtensionBinder.call] with the mapping block, `revoke` in `finally` — with its own
 * [CloudTimeouts] row for the budget (and, for `upload`, that row scaled by the byte count).
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
