package com.notesprout.android

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.notesprout.android.data.backup.DriveApiClient
import com.notesprout.android.data.backup.DriveAuth
import com.notesprout.android.data.backup.DriveTokenStore
import com.notesprout.android.databinding.ActivityDriveAuthBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * WebView-based Google OAuth 2.0 activity. Opens the Google consent page and intercepts
 * the redirect to http://localhost/oauth2callback to capture the authorization code.
 * Exchanges the code for tokens via PKCE — no GMS dependency.
 *
 * Returns RESULT_OK with EXTRA_EMAIL on success, RESULT_CANCELED on failure/dismiss.
 */
class DriveAuthActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_EMAIL = "drive_auth_email"
    }

    private lateinit var binding: ActivityDriveAuthBinding
    private var codeVerifier: String? = null
    private var authHandled = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (BuildConfig.DRIVE_CLIENT_ID.isBlank()) {
            Toast.makeText(
                this,
                "Drive not configured — set DRIVE_CLIENT_ID env var and rebuild.",
                Toast.LENGTH_LONG
            ).show()
            setResult(RESULT_CANCELED)
            finish()
            return
        }

        binding = ActivityDriveAuthBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnCancel.setOnClickListener {
            setResult(RESULT_CANCELED)
            finish()
        }

        val verifier = DriveAuth.generateCodeVerifier()
        codeVerifier = verifier
        val challenge = DriveAuth.generateCodeChallenge(verifier)
        val authUrl = DriveAuth.buildAuthUrl(challenge)

        binding.webView.settings.javaScriptEnabled = true
        // Google blocks OAuth in WebViews that identify as Android WebView (disallowed_useragent).
        // Spoofing a Chrome mobile UA bypasses the restriction while keeping the same flow.
        binding.webView.settings.userAgentString =
            "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        binding.webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url
                if (url.host == "localhost" && url.path == "/oauth2callback") {
                    if (!authHandled) {
                        authHandled = true
                        val code = url.getQueryParameter("code")
                        val error = url.getQueryParameter("error")
                        when {
                            code != null -> handleAuthCode(code)
                            else -> {
                                Toast.makeText(
                                    this@DriveAuthActivity,
                                    "Drive auth failed: ${error ?: "unknown"}",
                                    Toast.LENGTH_LONG
                                ).show()
                                setResult(RESULT_CANCELED)
                                finish()
                            }
                        }
                    }
                    return true
                }
                return false
            }
        }
        binding.webView.loadUrl(authUrl)
    }

    private fun handleAuthCode(code: String) {
        val verifier = codeVerifier ?: run {
            Toast.makeText(this, "Auth state lost — please try again.", Toast.LENGTH_LONG).show()
            setResult(RESULT_CANCELED)
            finish()
            return
        }
        lifecycleScope.launch {
            val (accessToken, refreshToken) = withContext(Dispatchers.IO) {
                DriveAuth.exchangeCodeForTokens(code, verifier)
            } ?: run {
                Toast.makeText(this@DriveAuthActivity, "Token exchange failed.", Toast.LENGTH_LONG).show()
                setResult(RESULT_CANCELED)
                finish()
                return@launch
            }

            if (refreshToken != null) {
                DriveTokenStore.storeRefreshToken(this@DriveAuthActivity, refreshToken)
            } else {
                // Should not happen with prompt=consent, but handle gracefully.
                Toast.makeText(
                    this@DriveAuthActivity,
                    "No refresh token received — Drive backup may require reconnecting after the session expires.",
                    Toast.LENGTH_LONG
                ).show()
            }

            val email = withContext(Dispatchers.IO) { DriveApiClient(accessToken).accountEmail() }
            setResult(RESULT_OK, Intent().putExtra(EXTRA_EMAIL, email))
            finish()
        }
    }
}
