package com.symmetricalpalmtree.notesproutsn.ext.drive

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * The OAuth 2.0 + PKCE core of the Drive provider (arc 25 / V2) — **pure**: strings in, strings
 * out, no network, no Android type, so every shape here is JVM-tested. [ConnectActivity] drives the
 * authorization half through a WebView; [DriveApi] drives the refresh half before each REST call.
 * The one door to the network for both is [DriveHttp].
 *
 * The flow, as og Notesprout runs it and Google documents it for a **Desktop-app** client
 * (`DRIVE_PLAN.md` § "standing traps"): a random verifier → its S256 challenge → the consent URL
 * with `access_type=offline&prompt=consent` (the only way a refresh token is guaranteed) → the
 * WebView is steered to `http://localhost/oauth2callback?code=…`, which no server answers and the
 * screen intercepts → the code + verifier + client secret are posted to the token endpoint →
 * an access token (short-lived, kept in memory) and a refresh token (kept in the host's store).
 *
 * **Secrets never leave this process and never land in a log**: the client secret, the verifier,
 * the code, both tokens. Nothing in this file logs at all.
 */
object DriveAuth {

    const val SCOPE: String = "https://www.googleapis.com/auth/drive.file"
    const val AUTH_URL: String = "https://accounts.google.com/o/oauth2/v2/auth"
    const val TOKEN_URL: String = "https://oauth2.googleapis.com/token"
    const val REVOKE_URL: String = "https://oauth2.googleapis.com/revoke"

    /** The redirect the consent page is sent to. No server listens there; the screen intercepts the
     *  navigation and reads the query. Registered on the OAuth client as-is. */
    const val REDIRECT_URI: String = "http://localhost/oauth2callback"
    const val REDIRECT_HOST: String = "localhost"
    const val REDIRECT_PATH: String = "/oauth2callback"

    /** The UA the WebView identifies as. Google refuses OAuth from anything that says "wv" /
     *  Android WebView (`disallowed_useragent`) — set **before** `loadUrl()`. */
    const val CHROME_UA: String =
        "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    /** How early an access token is treated as expired, so a call never starts on a token that
     *  dies mid-flight (an upload can run minutes). */
    const val EXPIRY_SKEW_MS: Long = 60_000L

    /** Google's default lifetime when the reply carries no `expires_in`. */
    const val DEFAULT_LIFETIME_S: Long = 3_600L

    private val json = Json { ignoreUnknownKeys = true }

    // ── PKCE (RFC 7636) ──────

    /** 32 random bytes as base64url without padding — 43 chars, inside the 43..128 the RFC allows. */
    fun codeVerifier(random: SecureRandom = SecureRandom()): String {
        val bytes = ByteArray(32).also { random.nextBytes(it) }
        return base64Url(bytes)
    }

    /** `BASE64URL(SHA256(ASCII(verifier)))`, no padding. */
    fun codeChallenge(verifier: String): String =
        base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    private fun base64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    // ── The consent URL ──────

    /** The URL the WebView opens. `prompt=consent` + `access_type=offline` is what makes Google
     *  issue a refresh token on every connect, not only the first. */
    fun authUrl(clientId: String, codeChallenge: String): String =
        AUTH_URL +
            "?client_id=${enc(clientId)}" +
            "&redirect_uri=${enc(REDIRECT_URI)}" +
            "&response_type=code" +
            "&scope=${enc(SCOPE)}" +
            "&code_challenge=${enc(codeChallenge)}" +
            "&code_challenge_method=S256" +
            "&access_type=offline" +
            "&prompt=consent"

    // ── The redirect ──────

    /** What the consent page sent back on the redirect. */
    sealed class Redirect {
        /** The authorization code to exchange. */
        class Code(val code: String) : Redirect()
        /** Google refused — `access_denied` when the person tapped Cancel on the consent page. */
        class Error(val error: String) : Redirect()
    }

    /** Whether [host] + [path] is the redirect the screen must intercept (never load — nothing
     *  answers there). */
    fun isRedirect(host: String?, path: String?): Boolean = host == REDIRECT_HOST && path == REDIRECT_PATH

    /**
     * Read the redirect's query: `code=` wins, else `error=`, else null (a redirect with neither is
     * malformed and the screen treats it as an error of its own). [query] is the raw query string,
     * `a=b&c=d`, percent-decoded here.
     */
    fun parseRedirect(query: String?): Redirect? {
        if (query.isNullOrEmpty()) return null
        var code: String? = null
        var error: String? = null
        for (pair in query.split('&')) {
            val eq = pair.indexOf('=')
            if (eq <= 0) continue
            val key = pair.substring(0, eq)
            val value = dec(pair.substring(eq + 1))
            when (key) {
                "code" -> if (value.isNotBlank()) code = value
                "error" -> if (value.isNotBlank()) error = value
            }
        }
        return when {
            code != null -> Redirect.Code(code)
            error != null -> Redirect.Error(error)
            else -> null
        }
    }

    // ── The token endpoint ──────

    /** The form body that trades the code for tokens. */
    fun exchangeBody(clientId: String, clientSecret: String, code: String, codeVerifier: String): String =
        "client_id=${enc(clientId)}" +
            "&client_secret=${enc(clientSecret)}" +
            "&code=${enc(code)}" +
            "&code_verifier=${enc(codeVerifier)}" +
            "&redirect_uri=${enc(REDIRECT_URI)}" +
            "&grant_type=authorization_code"

    /** The form body that trades a refresh token for a fresh access token. */
    fun refreshBody(clientId: String, clientSecret: String, refreshToken: String): String =
        "client_id=${enc(clientId)}" +
            "&client_secret=${enc(clientSecret)}" +
            "&refresh_token=${enc(refreshToken)}" +
            "&grant_type=refresh_token"

    /** The form body that revokes a token (refresh or access) — `disconnect`'s best-effort call. */
    fun revokeBody(token: String): String = "token=${enc(token)}"

    @Serializable
    private class TokenReply(
        val access_token: String? = null,
        val refresh_token: String? = null,
        val expires_in: Long? = null,
        val error: String? = null,
        val error_description: String? = null,
    )

    /** What the token endpoint answered, already sorted into the three things a caller does next. */
    sealed class TokenResult {
        /** A usable access token; [refreshToken] only on an exchange (a refresh answers none) and
         *  [expiresAtMs] already skewed by [EXPIRY_SKEW_MS]. */
        class Granted(val accessToken: String, val refreshToken: String?, val expiresAtMs: Long) : TokenResult()
        /** The refresh token is dead — revoked, expired, or the consent withdrawn. The provider must
         *  forget it: the account is NOT_CONNECTED from here. */
        object Revoked : TokenResult()
        /** Anything else: a malformed reply, another error code. Read as NETWORK by the caller —
         *  nothing changed and trying again is safe. [what] is the error code, never a token. */
        class Failed(val what: String) : TokenResult()
    }

    /**
     * Sort a token-endpoint reply. [http] is the status code; [body] the JSON. Google answers
     * `invalid_grant` with 400 for a dead refresh token or a reused/expired code — that is
     * [TokenResult.Revoked]; every other non-2xx or unreadable reply is [TokenResult.Failed].
     */
    fun parseTokenReply(http: Int, body: String, nowMs: Long): TokenResult {
        val reply = try {
            json.decodeFromString(TokenReply.serializer(), body)
        } catch (e: Exception) {
            return TokenResult.Failed("unreadable reply (http $http)")
        }
        if (reply.error == "invalid_grant") return TokenResult.Revoked
        if (http !in 200..299) return TokenResult.Failed(reply.error ?: "http $http")
        val access = reply.access_token
        if (access.isNullOrBlank()) return TokenResult.Failed(reply.error ?: "no access token")
        val lifetimeS = reply.expires_in ?: DEFAULT_LIFETIME_S
        val expiresAt = nowMs + lifetimeS * 1_000L - EXPIRY_SKEW_MS
        return TokenResult.Granted(access, reply.refresh_token?.takeIf { it.isNotBlank() }, expiresAt)
    }

    /** Whether an access token that expires at [expiresAtMs] (already skewed) is still usable now. */
    fun isFresh(expiresAtMs: Long, nowMs: Long): Boolean = nowMs < expiresAtMs

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
    private fun dec(s: String): String = try {
        java.net.URLDecoder.decode(s, "UTF-8")
    } catch (e: Exception) {
        s
    }
}
