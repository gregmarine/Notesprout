package com.symmetricalpalmtree.notesproutsn.ext.cloud

import com.symmetricalpalmtree.notesproutsn.core.Slog

/**
 * The access token, **in memory only** (arc 25 / V2).
 *
 * The refresh token is durable and lives in the host-encrypted extension store; the access token is
 * a sixty-minute credential and lives here, in this process, and dies with it. It is never written
 * to disk — not to the store, not anywhere — because a short-lived secret at rest is all cost and no
 * benefit, and because *the extension writes nothing to disk itself, ever*.
 *
 * Freshness is [DriveAuth.isFresh] against the already-skewed expiry, so a call never starts on a
 * token that would die mid-upload.
 */
class TokenCache {

    private var token: String? = null
    private var expiresAtMs: Long = 0L

    /** The cached token if it is still fresh at [nowMs], else null. */
    @Synchronized
    fun peek(nowMs: Long): String? {
        val t = token ?: return null
        return if (DriveAuth.isFresh(expiresAtMs, nowMs)) t else null
    }

    @Synchronized
    fun put(accessToken: String, expiresAtMs: Long) {
        this.token = accessToken
        this.expiresAtMs = expiresAtMs
    }

    @Synchronized
    fun clear() {
        token = null
        expiresAtMs = 0L
    }
}

/** The one cache for this process — shared by every bind, and by the connect screen, which primes
 *  it with the token it just won so the host's first operation costs no refresh. */
object DriveTokens {
    val cache: TokenCache = TokenCache()
}

/**
 * Where a bearer token comes from (arc 25 / V2): the cache if it is fresh, otherwise a silent
 * refresh against the token endpoint with the refresh token out of the store.
 *
 * The three outcomes of a refresh are the three states an account can be in:
 *  - **granted** — cache it and carry on;
 *  - **revoked** (Google's `invalid_grant`: the person removed the app, the token expired after
 *    months unused, the consent was withdrawn) — the refresh token is dead, so it is **forgotten
 *    along with the label** and the account is `not connected` from here. Leaving a dead token in
 *    the store would make `status()` lie forever;
 *  - **failed** — anything else; nothing changed, so it reads as `network` and trying again is safe.
 *
 * Nothing in this class logs a token, a code, or the account.
 */
class TokenSource(
    private val store: DriveStore,
    private val cache: TokenCache,
    private val transport: HttpTransport,
    private val clientId: String,
    private val clientSecret: String,
    private val now: () -> Long = { System.currentTimeMillis() },
) {

    /** A usable bearer token, refreshing if it must. */
    fun access(): String = cache.peek(now()) ?: refresh()

    /** Drop the cached token — the caller saw a 401 and wants the next call to refresh. */
    fun invalidate() = cache.clear()

    /** Trade the stored refresh token for a fresh access token. */
    fun refresh(): String {
        val refreshToken = store.value(DriveSql.Keys.REFRESH_TOKEN)?.takeIf { it.isNotBlank() }
            ?: throw DriveFailures.notConnected()
        val reply = transport.send(
            HttpRequest(
                method = "POST",
                url = DriveAuth.TOKEN_URL,
                body = HttpBody.Text(DriveHttp.FORM, DriveAuth.refreshBody(clientId, clientSecret, refreshToken)),
            )
        )
        return when (val result = DriveAuth.parseTokenReply(reply.code, reply.body, now())) {
            is DriveAuth.TokenResult.Granted -> {
                cache.put(result.accessToken, result.expiresAtMs)
                Slog.d(TAG) { "refresh granted" }
                result.accessToken
            }
            DriveAuth.TokenResult.Revoked -> {
                cache.clear()
                // Best effort: a store that cannot be reached still leaves the account refused.
                runCatching { store.clear() }
                Slog.d(TAG) { "refresh revoked — account forgotten" }
                throw DriveFailures.notConnected()
            }
            is DriveAuth.TokenResult.Failed -> {
                Slog.d(TAG) { "refresh failed: ${result.what}" }
                throw DriveFailures.network()
            }
        }
    }

    /**
     * `disconnect`'s best-effort revoke: tell Google the refresh token is finished. **Any failure is
     * swallowed** — a revoke that could not be made is not a reason to keep an account the person
     * asked to be rid of, and the local forget that follows is the part that matters.
     */
    fun revoke() {
        val refreshToken = try {
            store.value(DriveSql.Keys.REFRESH_TOKEN)
        } catch (e: Exception) {
            null
        }
        if (refreshToken.isNullOrBlank()) return
        try {
            val reply = transport.send(
                HttpRequest(
                    method = "POST",
                    url = DriveAuth.REVOKE_URL,
                    body = HttpBody.Text(DriveHttp.FORM, DriveAuth.revokeBody(refreshToken)),
                )
            )
            Slog.d(TAG) { "revoke http ${reply.code}" }
        } catch (e: Exception) {
            Slog.d(TAG) { "revoke failed (${e.javaClass.simpleName}) — forgetting locally anyway" }
        }
    }

    private companion object {
        const val TAG = "DriveTokens"
    }
}
