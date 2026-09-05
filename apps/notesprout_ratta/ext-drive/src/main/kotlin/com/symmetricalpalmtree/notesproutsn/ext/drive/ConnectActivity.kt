package com.symmetricalpalmtree.notesproutsn.ext.drive

import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.notesproutsn.core.Dialogs
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.core.TopGuard
import com.symmetricalpalmtree.notesproutsn.ext.drive.databinding.ActivityConnectBinding
import com.symmetricalpalmtree.notesproutsn.extension.HostCallerCheck
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The CLOUD_STORAGE_SCREEN point (arc 25 / V2) — **the sign-in, and the extension owns all of it**
 * (`DRIVE_PLAN.md` decision 3). The host has no INTERNET permission, no OAuth client and no idea
 * what a token is; it holds one bind for the showing, parks the store through `beginConnect`,
 * launches this screen **for a result**, and reads the truth back out of the next `status()`.
 *
 * **The caller check is the first thing that happens**, before anything is inflated: the screen is
 * exported (the host launches it by action) and only a `startActivityForResult` from the host
 * package gets in. A plain `am start` from a shell has a null `callingPackage` and is refused.
 *
 * The flow (`DriveAuth` holds every pure piece of it, JVM-tested):
 *  1. a random PKCE verifier and its S256 challenge;
 *  2. Google's consent page in a WebView identifying as **Chrome** — Google refuses OAuth from
 *     anything that says "wv" (`disallowed_useragent`), and the UA must be set **before** the load;
 *  3. the consent page is steered to `http://localhost/oauth2callback`, which no server answers.
 *     The navigation is **intercepted** — nothing is ever loaded from it, which is also why this
 *     APK needs no cleartext-traffic permission;
 *  4. the code is exchanged on IO for an access token and a refresh token;
 *  5. the refresh token and the account's label are written **through the store the host lent**
 *     ([ConnectSession]) — the extension writes nothing to disk itself, ever;
 *  6. and only then `RESULT_OK`. Any other ending is `RESULT_CANCELED`, so a half-finished sign-in
 *     can never read as a connected account.
 *
 * **The screen leaves on a dialog's dismiss, never beside it** — finishing on the next line tears
 * the window down before the dialog is drawn and the screen flashes and vanishes with nothing said.
 *
 * Nothing here logs the code, either token, or the account. `Slog` lines are shape only.
 */
class ConnectActivity : AppCompatActivity() {

    private lateinit var binding: ActivityConnectBinding
    private lateinit var store: DriveStore

    private var verifier: String = ""

    /** The redirect is answered exactly once: a consent page can navigate more than once, and a
     *  second exchange of the same code would fail as `invalid_grant` and read as revoked. */
    private val authHandled = AtomicBoolean(false)

    /** What the exchange came back with. Sorted on IO so the main thread only draws. */
    private sealed class Outcome {
        object Connected : Outcome()
        class Problem(val messageRes: Int) : Outcome()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!HostCallerCheck.enforceActivity(this, BuildConfig.HOST_PACKAGE)) return

        // Cancel is the default ending; only a completed, stored sign-in overwrites it.
        setResult(RESULT_CANCELED)

        if (!DriveService.configured()) {
            // The host already dialogs on `configured = false` before it ever gets here, so there is
            // nothing for this screen to explain — it simply is not a door that exists.
            Slog.d(TAG) { "not configured — refusing" }
            finish()
            return
        }

        val parked = ConnectSession.store
        if (parked == null) {
            // The bracket was never opened (or the host went away). Nothing can be saved, so there
            // is no point signing in.
            Slog.d(TAG) { "no store parked — refusing" }
            finish()
            return
        }
        store = DriveStore(parked)

        binding = ActivityConnectBinding.inflate(layoutInflater)
        setContentView(binding.root)
        TopGuard.applyInsetPadding(binding.root)

        binding.btnCancel.setOnClickListener { finish() }
        binding.btnCancel.setOnLongClickListener {
            android.widget.Toast.makeText(this, R.string.cd_drive_connect_cancel, android.widget.Toast.LENGTH_SHORT).show()
            true
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = finish()
        })

        verifier = DriveAuth.codeVerifier()
        startSignIn(DriveAuth.authUrl(BuildConfig.DRIVE_CLIENT_ID, DriveAuth.codeChallenge(verifier)))
    }

    private fun startSignIn(url: String) {
        val web: WebView = binding.web
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        // BEFORE loadUrl — the standing trap: Google refuses an Android WebView UA outright.
        web.settings.userAgentString = DriveAuth.CHROME_UA
        web.webViewClient = object : WebViewClient() {

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val uri = request?.url ?: return false
                if (!DriveAuth.isRedirect(uri.host, uri.path)) return false
                handleRedirect(uri)
                return true
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                // A belt-and-braces catch: some WebView builds start the navigation before asking.
                val uri = url?.let { runCatching { Uri.parse(it) }.getOrNull() } ?: return
                if (DriveAuth.isRedirect(uri.host, uri.path)) {
                    view?.stopLoading()
                    handleRedirect(uri)
                }
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame != true) return
                // The redirect never loads, so an error on it is ours, not a real failure.
                val uri = request.url
                if (uri != null && DriveAuth.isRedirect(uri.host, uri.path)) return
                if (authHandled.get()) return
                Slog.d(TAG) { "main-frame load failed" }
                fail(R.string.drive_connect_offline)
            }
        }
        web.loadUrl(url)
    }

    private fun handleRedirect(uri: Uri) {
        if (!authHandled.compareAndSet(false, true)) return
        when (val redirect = DriveAuth.parseRedirect(uri.encodedQuery)) {
            is DriveAuth.Redirect.Code -> exchange(redirect.code)
            is DriveAuth.Redirect.Error -> {
                if (redirect.error == ACCESS_DENIED) {
                    // The person tapped Cancel on Google's page. Nothing to explain.
                    Slog.d(TAG) { "consent declined" }
                    finish()
                } else {
                    Slog.d(TAG) { "consent error: ${redirect.error}" }
                    fail(R.string.drive_connect_failed)
                }
            }
            null -> {
                Slog.d(TAG) { "redirect carried neither code nor error" }
                fail(R.string.drive_connect_failed)
            }
        }
    }

    /** Trade the code for tokens, write them, and answer the host. */
    private fun exchange(code: String) {
        // The glass shows a sentence, not a page nobody should tap.
        binding.web.visibility = View.GONE
        binding.busy.visibility = View.VISIBLE

        lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO) { exchangeOnIo(code) }
            if (isFinishing || isDestroyed) return@launch
            when (outcome) {
                Outcome.Connected -> {
                    Slog.d(TAG) { "connected" }
                    setResult(RESULT_OK)
                    finish()
                }
                is Outcome.Problem -> fail(outcome.messageRes)
            }
        }
    }

    /** Blocking, on IO. Never logs a token, a code, or the account. */
    private fun exchangeOnIo(code: String): Outcome {
        val reply = try {
            DriveHttp.send(
                HttpRequest(
                    method = "POST",
                    url = DriveAuth.TOKEN_URL,
                    body = HttpBody.Text(
                        DriveHttp.FORM,
                        DriveAuth.exchangeBody(
                            BuildConfig.DRIVE_CLIENT_ID,
                            BuildConfig.DRIVE_CLIENT_SECRET,
                            code,
                            verifier,
                        ),
                    ),
                )
            )
        } catch (e: Exception) {
            Slog.d(TAG) { "exchange failed (${e.javaClass.simpleName})" }
            return Outcome.Problem(R.string.drive_connect_offline)
        }

        val granted = when (val result = DriveAuth.parseTokenReply(reply.code, reply.body, System.currentTimeMillis())) {
            is DriveAuth.TokenResult.Granted -> result
            DriveAuth.TokenResult.Revoked -> {
                Slog.d(TAG) { "exchange rejected" }
                return Outcome.Problem(R.string.drive_connect_failed)
            }
            is DriveAuth.TokenResult.Failed -> {
                Slog.d(TAG) { "exchange failed: ${result.what}" }
                return Outcome.Problem(R.string.drive_connect_failed)
            }
        }

        val refreshToken = granted.refreshToken
        if (refreshToken.isNullOrBlank()) {
            // `prompt=consent&access_type=offline` should always give one. Without it the account
            // would work until the access token died and then quietly stop — worse than refusing.
            Slog.d(TAG) { "no refresh token in the grant" }
            return Outcome.Problem(R.string.drive_connect_no_refresh)
        }

        // Best effort, and deliberately so: a connection with no label is legal on the wire, and
        // refusing a sign-in over a display string would be absurd.
        val label = DriveJson.label(DriveApi.aboutEmail(DriveHttp, granted.accessToken))

        return try {
            store.put(DriveSql.Keys.REFRESH_TOKEN, refreshToken)
            store.put(DriveSql.Keys.ACCOUNT_LABEL, label)
            // The host's very next call costs no refresh.
            DriveTokens.cache.put(granted.accessToken, granted.expiresAtMs)
            Outcome.Connected
        } catch (e: StoreUnavailable) {
            Slog.d(TAG) { "store unavailable — the grant is dropped" }
            DriveTokens.cache.clear()
            Outcome.Problem(R.string.drive_connect_store_failed)
        }
    }

    /** Say what went wrong, and leave **on the dialog's dismiss**. */
    private fun fail(messageRes: Int) {
        if (isFinishing || isDestroyed) return
        binding.web.visibility = View.GONE
        binding.busy.visibility = View.GONE
        Dialogs.confirm(this, getString(R.string.drive_connect_problem_title), getString(messageRes)) { finish() }
    }

    override fun onDestroy() {
        if (this::binding.isInitialized) {
            runCatching {
                binding.web.stopLoading()
                binding.web.destroy()
            }
        }
        super.onDestroy()
    }

    private companion object {
        const val TAG = "DriveConnect"

        /** Google's code for "the person said no on the consent page". */
        const val ACCESS_DENIED = "access_denied"
    }
}
