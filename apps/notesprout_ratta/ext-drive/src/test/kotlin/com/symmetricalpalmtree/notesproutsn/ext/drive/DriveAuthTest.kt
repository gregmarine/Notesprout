package com.symmetricalpalmtree.notesproutsn.ext.drive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

class DriveAuthTest {

    // ── PKCE ──────

    @Test
    fun verifierIs43UrlSafeCharsWithoutPadding() {
        val v = DriveAuth.codeVerifier()
        assertEquals(43, v.length)
        assertTrue(v.all { it.isLetterOrDigit() || it == '-' || it == '_' })
    }

    @Test
    fun verifiersDiffer() {
        assertTrue(DriveAuth.codeVerifier() != DriveAuth.codeVerifier())
    }

    @Test
    fun challengeIsRfc7636Vector() {
        // RFC 7636 appendix B: the verifier and its S256 challenge.
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", DriveAuth.codeChallenge(verifier))
    }

    @Test
    fun verifierFromSeededRandomIsDeterministic() {
        val a = DriveAuth.codeVerifier(SecureRandom.getInstance("SHA1PRNG").apply { setSeed(7L) })
        val b = DriveAuth.codeVerifier(SecureRandom.getInstance("SHA1PRNG").apply { setSeed(7L) })
        assertEquals(a, b)
    }

    // ── The consent URL ──────

    @Test
    fun authUrlCarriesEveryParameterAndEncodes() {
        val url = DriveAuth.authUrl("id 1", "chal")
        assertTrue(url.startsWith(DriveAuth.AUTH_URL + "?"))
        assertTrue(url.contains("client_id=id+1"))
        assertTrue(url.contains("redirect_uri=http%3A%2F%2Flocalhost%2Foauth2callback"))
        assertTrue(url.contains("response_type=code"))
        assertTrue(url.contains("scope=https%3A%2F%2Fwww.googleapis.com%2Fauth%2Fdrive.file"))
        assertTrue(url.contains("code_challenge=chal"))
        assertTrue(url.contains("code_challenge_method=S256"))
        assertTrue(url.contains("access_type=offline"))
        assertTrue(url.contains("prompt=consent"))
    }

    // ── The redirect ──────

    @Test
    fun redirectIsRecognizedByHostAndPathOnly() {
        assertTrue(DriveAuth.isRedirect("localhost", "/oauth2callback"))
        assertFalse(DriveAuth.isRedirect("accounts.google.com", "/oauth2callback"))
        assertFalse(DriveAuth.isRedirect("localhost", "/"))
        assertFalse(DriveAuth.isRedirect(null, null))
    }

    @Test
    fun redirectCodeWins() {
        val r = DriveAuth.parseRedirect("code=4%2FabcDEF&scope=x")
        assertTrue(r is DriveAuth.Redirect.Code)
        assertEquals("4/abcDEF", (r as DriveAuth.Redirect.Code).code)
    }

    @Test
    fun redirectErrorWhenNoCode() {
        val r = DriveAuth.parseRedirect("error=access_denied")
        assertTrue(r is DriveAuth.Redirect.Error)
        assertEquals("access_denied", (r as DriveAuth.Redirect.Error).error)
    }

    @Test
    fun redirectWithNeitherIsNull() {
        assertNull(DriveAuth.parseRedirect(null))
        assertNull(DriveAuth.parseRedirect(""))
        assertNull(DriveAuth.parseRedirect("state=abc&=x&novalue"))
        assertNull(DriveAuth.parseRedirect("code="))
    }

    // ── The bodies ──────

    @Test
    fun exchangeBodyIsTheFullGrant() {
        val body = DriveAuth.exchangeBody("cid", "sec&", "co de", "ver")
        assertEquals(
            "client_id=cid&client_secret=sec%26&code=co+de&code_verifier=ver" +
                "&redirect_uri=http%3A%2F%2Flocalhost%2Foauth2callback&grant_type=authorization_code",
            body,
        )
    }

    @Test
    fun refreshBodyIsTheRefreshGrant() {
        assertEquals(
            "client_id=cid&client_secret=sec&refresh_token=rt&grant_type=refresh_token",
            DriveAuth.refreshBody("cid", "sec", "rt"),
        )
    }

    @Test
    fun revokeBodyIsTheToken() {
        assertEquals("token=a%2Fb", DriveAuth.revokeBody("a/b"))
    }

    // ── The token reply ──────

    @Test
    fun grantedReplyCarriesBothTokensAndSkewedExpiry() {
        val r = DriveAuth.parseTokenReply(
            200,
            """{"access_token":"at","refresh_token":"rt","expires_in":3599,"scope":"s","token_type":"Bearer"}""",
            nowMs = 1_000_000L,
        )
        assertTrue(r is DriveAuth.TokenResult.Granted)
        r as DriveAuth.TokenResult.Granted
        assertEquals("at", r.accessToken)
        assertEquals("rt", r.refreshToken)
        assertEquals(1_000_000L + 3_599_000L - DriveAuth.EXPIRY_SKEW_MS, r.expiresAtMs)
    }

    @Test
    fun refreshReplyHasNoRefreshTokenAndDefaultLifetime() {
        val r = DriveAuth.parseTokenReply(200, """{"access_token":"at"}""", nowMs = 0L)
        r as DriveAuth.TokenResult.Granted
        assertNull(r.refreshToken)
        assertEquals(DriveAuth.DEFAULT_LIFETIME_S * 1_000L - DriveAuth.EXPIRY_SKEW_MS, r.expiresAtMs)
    }

    @Test
    fun invalidGrantIsRevokedWhateverTheStatus() {
        assertTrue(DriveAuth.parseTokenReply(400, """{"error":"invalid_grant","error_description":"Token has been expired or revoked."}""", 0L) is DriveAuth.TokenResult.Revoked)
        assertTrue(DriveAuth.parseTokenReply(200, """{"error":"invalid_grant"}""", 0L) is DriveAuth.TokenResult.Revoked)
    }

    @Test
    fun otherErrorsAndBadRepliesAreFailed() {
        val a = DriveAuth.parseTokenReply(500, """{"error":"internal"}""", 0L)
        assertTrue(a is DriveAuth.TokenResult.Failed)
        assertEquals("internal", (a as DriveAuth.TokenResult.Failed).what)
        val b = DriveAuth.parseTokenReply(200, "not json", 0L)
        assertTrue(b is DriveAuth.TokenResult.Failed)
        val c = DriveAuth.parseTokenReply(200, """{"token_type":"Bearer"}""", 0L)
        assertTrue(c is DriveAuth.TokenResult.Failed)
        val d = DriveAuth.parseTokenReply(401, """{}""", 0L)
        assertEquals("http 401", (d as DriveAuth.TokenResult.Failed).what)
    }

    @Test
    fun freshnessIsStrict() {
        assertTrue(DriveAuth.isFresh(100L, 99L))
        assertFalse(DriveAuth.isFresh(100L, 100L))
    }
}
