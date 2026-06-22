package com.notesprout.android.data.backup

import android.content.Context
import android.util.Base64
import android.util.Log
import com.notesprout.android.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

object DriveAuth {
    const val SCOPE_DRIVE_FILE = "https://www.googleapis.com/auth/drive.file"
    private const val AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth"
    private const val TOKEN_URL = "https://oauth2.googleapis.com/token"

    // Redirect URI intercepted in DriveAuthActivity's WebView — no server needed.
    const val REDIRECT_URI = "http://localhost/oauth2callback"

    sealed interface TokenResult {
        data class Token(val accessToken: String) : TokenResult
        data class Error(val message: String) : TokenResult
    }

    // RFC 7636 PKCE — 32 random bytes → base64url, no padding
    fun generateCodeVerifier(): String {
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    fun generateCodeChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    fun buildAuthUrl(codeChallenge: String): String {
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        return "$AUTH_URL" +
            "?client_id=${enc(BuildConfig.DRIVE_CLIENT_ID)}" +
            "&redirect_uri=${enc(REDIRECT_URI)}" +
            "&response_type=code" +
            "&scope=${enc(SCOPE_DRIVE_FILE)}" +
            "&code_challenge=${enc(codeChallenge)}" +
            "&code_challenge_method=S256" +
            "&access_type=offline" +
            "&prompt=consent"
    }

    @Serializable
    private data class TokenResponse(
        val access_token: String? = null,
        val refresh_token: String? = null,
        val error: String? = null,
        val error_description: String? = null,
    )

    private val json = Json { ignoreUnknownKeys = true }

    /** Exchange authorization code for access + refresh tokens. Returns null on failure. */
    suspend fun exchangeCodeForTokens(
        code: String,
        codeVerifier: String,
    ): Pair<String, String?>? = withContext(Dispatchers.IO) {
        try {
            fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
            val body = "client_id=${enc(BuildConfig.DRIVE_CLIENT_ID)}" +
                "&client_secret=${enc(BuildConfig.DRIVE_CLIENT_SECRET)}" +
                "&code=${enc(code)}" +
                "&code_verifier=${enc(codeVerifier)}" +
                "&redirect_uri=${enc(REDIRECT_URI)}" +
                "&grant_type=authorization_code"
            val resp = postForm(TOKEN_URL, body) ?: return@withContext null
            val tr = json.decodeFromString<TokenResponse>(resp)
            val token = tr.access_token ?: return@withContext null
            Pair(token, tr.refresh_token)
        } catch (e: Exception) {
            Log.e("DriveAuth", "exchangeCodeForTokens failed", e)
            null
        }
    }

    /**
     * Silently refreshes an access token using the stored refresh token.
     * Called by the backup engine each run — no UI interaction.
     */
    suspend fun getAccessTokenSilent(context: Context): TokenResult = withContext(Dispatchers.IO) {
        if (BuildConfig.DRIVE_CLIENT_ID.isBlank() || BuildConfig.DRIVE_CLIENT_SECRET.isBlank()) {
            return@withContext TokenResult.Error(
                "Drive OAuth not configured — add DRIVE_CLIENT_ID and DRIVE_CLIENT_SECRET to local.properties."
            )
        }
        val refreshToken = DriveTokenStore.getRefreshToken(context)
            ?: return@withContext TokenResult.Error(
                "Not connected to Google Drive — tap 'Connect Google Drive' in Backup Settings."
            )
        try {
            fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
            val body = "client_id=${enc(BuildConfig.DRIVE_CLIENT_ID)}" +
                "&client_secret=${enc(BuildConfig.DRIVE_CLIENT_SECRET)}" +
                "&refresh_token=${enc(refreshToken)}" +
                "&grant_type=refresh_token"
            val resp = postForm(TOKEN_URL, body)
                ?: return@withContext TokenResult.Error("Token refresh request failed.")
            val tr = json.decodeFromString<TokenResponse>(resp)
            val token = tr.access_token
            if (token.isNullOrBlank()) {
                TokenResult.Error(tr.error_description ?: tr.error ?: "No access token in refresh response.")
            } else {
                TokenResult.Token(token)
            }
        } catch (e: Exception) {
            TokenResult.Error(e.message ?: "Token refresh failed.")
        }
    }

    private fun postForm(url: String, body: String): String? {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.connectTimeout = 30_000
            conn.readTimeout = 30_000
            conn.doOutput = true
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code in 200..299) {
                conn.inputStream.bufferedReader().readText()
            } else {
                Log.e("DriveAuth", "postForm HTTP $code")
                conn.errorStream?.bufferedReader()?.readText()
            }
        } finally {
            conn.disconnect()
        }
    }
}
