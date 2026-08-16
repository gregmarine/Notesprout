package com.symmetricalpalmtree.notesprout.bootstrap

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.notesprout.R
import com.symmetricalpalmtree.notesprout.core.TopGuard
import com.symmetricalpalmtree.notesprout.crypto.AttemptLimiter
import com.symmetricalpalmtree.notesprout.crypto.PassphraseStore
import com.symmetricalpalmtree.notesprout.data.index.PaperIndex
import com.symmetricalpalmtree.notesprout.databinding.ActivityUnlockBinding
import com.symmetricalpalmtree.notesprout.library.LibraryActivity
import kotlinx.coroutines.launch

/**
 * Passphrase (recovery key) entry after a reinstall / restore, when no cached key opens the index.
 * Rate-limited by [AttemptLimiter]; a wrong key shows an error and the file is untouched
 * (verification is a read-only open through `SoilCrypto`). No "forgot" path in v0 — the recovery
 * key IS the passphrase. Never launched while the index is open.
 */
class UnlockActivity : AppCompatActivity() {

    private lateinit var binding: ActivityUnlockBinding
    private val handler = Handler(Looper.getMainLooper())
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (PaperIndex.isReady()) { startActivity(Intent(this, LibraryActivity::class.java)); finish(); return }
        binding = ActivityUnlockBinding.inflate(layoutInflater)
        setContentView(binding.root)
        TopGuard.applyInsetPadding(binding.root, followIme = true)

        binding.btnUnlock.setOnClickListener { attempt() }
        binding.keyInput.setOnEditorActionListener { _, _, _ -> attempt(); true }
        refreshLockout()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    /** Show/hide the lockout countdown; the entry row is hidden (GONE) while locked out. */
    private fun refreshLockout() {
        val until = AttemptLimiter.check(this)
        val remaining = until - System.currentTimeMillis()
        if (remaining > 0) {
            binding.entryRow.visibility = View.GONE
            binding.lockoutText.visibility = View.VISIBLE
            binding.lockoutText.text = getString(R.string.unlock_locked_out, formatSeconds(remaining))
            handler.postDelayed({ refreshLockout() }, 1000L)
        } else {
            binding.lockoutText.visibility = View.GONE
            binding.entryRow.visibility = View.VISIBLE
        }
    }

    private fun attempt() {
        if (busy) return
        val typed = binding.keyInput.text?.toString()?.trim().orEmpty()
        if (typed.isEmpty()) return
        if (AttemptLimiter.check(this) > System.currentTimeMillis()) { refreshLockout(); return }
        hideIme()
        busy = true
        binding.errorText.visibility = View.GONE
        binding.progressText.visibility = View.VISIBLE
        lifecycleScope.launch {
            // Recovery keys are upper-case Crockford; accept a lower-case transcription too.
            var ok = PaperIndex.unlockAndOpen(this@UnlockActivity, typed)
            val upper = typed.uppercase()
            if (!ok && upper != typed) ok = PaperIndex.unlockAndOpen(this@UnlockActivity, upper)
            busy = false
            binding.progressText.visibility = View.GONE
            if (ok) {
                AttemptLimiter.recordSuccess(this@UnlockActivity)
                // The user just typed the key — they have it; don't show the reveal screen again.
                PassphraseStore.setRecoveryKeyAcknowledged(this@UnlockActivity)
                startActivity(Intent(this@UnlockActivity, LibraryActivity::class.java))
                finish()
            } else {
                AttemptLimiter.recordFailure(this@UnlockActivity)
                binding.errorText.visibility = View.VISIBLE
                binding.keyInput.text?.clear()
                refreshLockout()
            }
        }
    }

    private fun hideIme() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.keyInput.windowToken, 0)
    }

    private fun formatSeconds(ms: Long): String {
        val s = (ms + 999) / 1000
        return if (s >= 60) "${s / 60} min ${s % 60} s" else "$s s"
    }
}
