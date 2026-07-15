package com.notesprout.android

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.notesprout.android.crypto.PassphrasePrompt
import com.notesprout.android.data.index.NotesproutIndex
import kotlinx.coroutines.launch

/**
 * Launcher gate. Prepares the encrypted index — a one-time plaintext→encrypted migration on the
 * first launch after this update, or an unlock prompt on a device with no cached key — before any
 * real UI. Then forwards the (possibly deep-link) intent to [MainActivity], so its cold-launch
 * surface restore and `.soil` import paths run exactly as before.
 *
 * [MainActivity] also self-guards (bounces here if it is ever entered without a ready index), so this
 * gate covers every cold entry, not just the launcher.
 */
class BootstrapActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(preparingView())
        lifecycleScope.launch { boot() }
    }

    private suspend fun boot() {
        val outcome = NotesproutIndex.ensureReady(this)
        if (outcome == NotesproutIndex.PrepareOutcome.NEEDS_UNLOCK) {
            var message = "Enter your global passphrase (or recovery key) to open your library on this device."
            while (true) {
                val pass = PassphrasePrompt.promptForPassphrase(this, "Unlock Notesprout", message)
                if (pass == null) { finishAffinity(); return } // declined → leave the app locked
                if (NotesproutIndex.unlockAndOpen(this, pass)) break
                message = "That passphrase didn't open your library. Try again."
            }
        }
        forwardToMain()
    }

    /** Reuse the received intent (launcher MAIN, or a forwarded .soil VIEW/SEND) so MainActivity sees
     *  a normal cold launch — its restore/import logic is untouched. */
    private fun forwardToMain() {
        startActivity(Intent(intent).setClass(this, MainActivity::class.java))
        finish()
    }

    private fun preparingView(): LinearLayout {
        val ink = ContextCompat.getColor(this, R.color.inkBlack)
        val light = ContextCompat.getColor(this, R.color.inkLight)
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(ContextCompat.getColor(this@BootstrapActivity, R.color.paperWhite))
            addView(TextView(this@BootstrapActivity).apply {
                text = getString(R.string.app_name)
                textSize = 22f
                setTextColor(ink)
                gravity = Gravity.CENTER
            })
            addView(TextView(this@BootstrapActivity).apply {
                text = "Preparing your library…"
                textSize = 14f
                setTextColor(light)
                gravity = Gravity.CENTER
                setPadding(0, 24, 0, 0)
            }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        }
    }
}
