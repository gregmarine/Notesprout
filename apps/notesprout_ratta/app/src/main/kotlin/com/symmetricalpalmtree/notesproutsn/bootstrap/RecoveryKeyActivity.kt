package com.symmetricalpalmtree.notesproutsn.bootstrap

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.symmetricalpalmtree.notesproutsn.R
import com.symmetricalpalmtree.notesproutsn.core.IndexGuard
import com.symmetricalpalmtree.notesproutsn.core.TopGuard
import com.symmetricalpalmtree.notesproutsn.crypto.PassphraseStore
import com.symmetricalpalmtree.notesproutsn.databinding.ActivityRecoveryKeyBinding
import com.symmetricalpalmtree.notesproutsn.library.LibraryActivity

/**
 * First launch only: reveals the auto-minted recovery key (the global passphrase). Continue requires
 * the "I've saved it" checkbox; the acknowledgement is persisted so the screen is never shown again.
 * The key is displayed and copied to the clipboard on request — never logged, never in an Intent.
 */
class RecoveryKeyActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!IndexGuard.ready(this)) return
        val binding = ActivityRecoveryKeyBinding.inflate(layoutInflater)
        setContentView(binding.root)
        TopGuard.applyInsetPadding(binding.root)

        val key = PassphraseStore.getGlobalPassphrase(this)
        if (key == null) {
            // Cannot happen after a successful bootstrap; don't strand the user on an empty screen.
            startActivity(Intent(this, LibraryActivity::class.java)); finish(); return
        }
        binding.keyText.text = key

        binding.btnCopy.setOnClickListener {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText(getString(R.string.recovery_clip_label), key))
            Toast.makeText(this, R.string.recovery_copied, Toast.LENGTH_SHORT).show()
        }
        binding.btnContinue.setOnClickListener {
            if (!binding.checkSaved.isChecked) {
                Toast.makeText(this, R.string.recovery_tick_first, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            PassphraseStore.setRecoveryKeyAcknowledged(this)
            startActivity(Intent(this, LibraryActivity::class.java))
            finish()
        }
    }
}
