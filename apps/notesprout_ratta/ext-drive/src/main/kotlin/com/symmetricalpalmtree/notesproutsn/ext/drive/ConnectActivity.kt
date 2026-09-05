package com.symmetricalpalmtree.notesproutsn.ext.drive

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.symmetricalpalmtree.notesproutsn.extension.HostCallerCheck

/**
 * The CLOUD_STORAGE_SCREEN point (arc 25 / V1) — scaffold only. The WebView PKCE sign-in
 * (`DRIVE_PLAN.md` decision 3: Chrome UA spoof, `http://localhost/oauth2callback` redirect
 * intercept, the code exchange) lands here in V2. **V1 only proves the door resolves and refuses a
 * non-host caller** — no layout, no network, nothing else.
 */
class ConnectActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!HostCallerCheck.enforceActivity(this, BuildConfig.HOST_PACKAGE)) return
        setResult(RESULT_CANCELED)
        finish()
    }
}
