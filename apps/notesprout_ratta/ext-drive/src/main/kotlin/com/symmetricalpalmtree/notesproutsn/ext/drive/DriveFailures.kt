package com.symmetricalpalmtree.notesproutsn.ext.drive

import com.symmetricalpalmtree.notesproutsn.extension.CloudContract
import java.io.IOException
import java.security.GeneralSecurityException

/**
 * **Every refusal this provider can make, minted in one place** (arc 25 / V2). The seam allows only
 * three exception types across a stub, and the host compares exactly two messages **verbatim**
 * ([CloudContract.NOT_CONNECTED], [CloudContract.NETWORK]) — so the mapping table has to live
 * somewhere it can be read at a glance and tested without a network.
 *
 * The table:
 *
 * | what happened | what crosses |
 * |---|---|
 * | offline, DNS, TLS, a socket timeout, any `IOException` | `IllegalStateException("network")` |
 * | http 5xx, or 429 (rate limited) | `IllegalStateException("network")` |
 * | no refresh token in the store, or Google says the token is dead | `IllegalStateException("not connected")` |
 * | any other 4xx (400 / 403 / 404 on a specific call) | `IllegalStateException("http <code>")` |
 * | anything else at all (a serialization failure, an NPE) | `IllegalStateException("provider failure (<class>)")` |
 *
 * The last row is the one that matters most: a **non-marshalable** exception leaving a stub kills
 * the Binder transaction silently and the host waits out its whole timeout for nothing (the arc-2
 * trap). Everything is funnelled through [marshalable] on the way out.
 *
 * No message ever carries user content — no file name, no email, no URL, no token.
 */
object DriveFailures {

    /** The host offers Connect. */
    fun notConnected(): IllegalStateException = IllegalStateException(CloudContract.NOT_CONNECTED)

    /** Nothing changed; the host offers to try again. */
    fun network(): IllegalStateException = IllegalStateException(CloudContract.NETWORK)

    /** A status code that means "ask again later, it is not about you". */
    fun isRetryable(code: Int): Boolean = code >= 500 || code == 429

    /** The refusal for a failed http call: retryable codes read as the network being unwell,
     *  everything else names the code so a walk can find it in a log. */
    fun forHttp(code: Int): IllegalStateException =
        if (isRetryable(code)) network() else IllegalStateException("http $code")

    /** Whether [e] is the network failing rather than the provider refusing. `IOException` covers
     *  `UnknownHostException`, `SocketTimeoutException` and every `SSLException`;
     *  `GeneralSecurityException` catches the handful of TLS failures that are not. */
    fun isNetwork(e: Throwable): Boolean = e is IOException || e is GeneralSecurityException

    /**
     * The last gate before a stub returns. `SecurityException`, `IllegalArgumentException` and
     * `IllegalStateException` are already the three the seam allows and pass through untouched; a
     * store that cannot be reached becomes the provider's `STORE_UNAVAILABLE`; everything else
     * becomes an `IllegalStateException` naming only the exception's class.
     */
    fun marshalable(e: Throwable): RuntimeException = when (e) {
        is SecurityException -> e
        is IllegalArgumentException -> e
        is IllegalStateException -> e
        is StoreUnavailable -> IllegalStateException(DriveService.STORE_UNAVAILABLE)
        else -> if (isNetwork(e)) network() else IllegalStateException("provider failure (${e.javaClass.simpleName})")
    }
}
