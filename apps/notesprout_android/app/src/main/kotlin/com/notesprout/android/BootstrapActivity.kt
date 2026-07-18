package com.notesprout.android

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
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

    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Just a paper-white screen up front — the warm-cache index open is instant, so a normal
        // launch should show no text (the old "Preparing your library…" flashed as an awkward blip).
        val root = FrameLayout(this).apply {
            setBackgroundColor(ContextCompat.getColor(this@BootstrapActivity, R.color.paperWhite))
        }
        setContentView(root)
        // Reveal the message only if prep is actually slow: a first-run migration, a cold
        // key-derivation, or while an unlock prompt is up. Cancelled the moment boot() finishes.
        val reveal = Runnable { root.addView(preparingView()) }
        handler.postDelayed(reveal, REVEAL_DELAY_MS)
        lifecycleScope.launch {
            try { boot() } finally { handler.removeCallbacks(reveal) }
        }
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
        forwardNext()
    }

    /** Forward to the next screen so it sees a normal cold launch. First launch routes through
     *  onboarding (recovery-key reveal); otherwise straight to MainActivity, whose restore/import
     *  logic is untouched.
     *
     *  We build a *clean* in-app intent rather than reusing the received one. Reusing it drags the
     *  launcher's `CATEGORY_LAUNCHER` + `NEW_TASK|RESET_TASK_IF_NEEDED` flags onto this internal
     *  start, which re-triggers launcher task routing — and once the encrypted-index open delays
     *  this hand-off by a beat, the window lands back on the home screen instead of the app (a
     *  hard-to-see "opens then closes" on BOOX). Only a genuine `.soil` deep-link (VIEW/SEND)
     *  payload is carried through, so import still works. */
    private fun forwardNext() {
        val next = if (OnboardingActivity.shouldShow(this)) OnboardingActivity::class.java
                   else MainActivity::class.java
        val forward = Intent(this, next)
        val src = intent
        if (src.action == Intent.ACTION_VIEW || src.action == Intent.ACTION_SEND) {
            forward.action = src.action
            forward.setDataAndType(src.data, src.type)
            src.extras?.let { forward.putExtras(it) }
            forward.addFlags(src.flags and
                (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
        }
        startActivity(forward)
        finish()
    }

    private fun preparingView(): LinearLayout {
        val ink = ContextCompat.getColor(this, R.color.inkBlack)
        val light = ContextCompat.getColor(this, R.color.inkLight)
        return LinearLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
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

    companion object {
        /** Grace period before showing "Preparing your library…" — long enough that a warm-cache
         *  (instant) open never flashes text, short enough to reassure on a genuinely slow prep. */
        private const val REVEAL_DELAY_MS = 450L
    }
}
