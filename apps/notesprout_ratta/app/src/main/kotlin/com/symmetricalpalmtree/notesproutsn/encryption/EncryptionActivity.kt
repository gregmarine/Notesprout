package com.symmetricalpalmtree.notesproutsn.encryption

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.TooltipCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.notesproutsn.R
import com.symmetricalpalmtree.notesproutsn.core.Dialogs
import com.symmetricalpalmtree.notesproutsn.core.IndexGuard
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.core.TopGuard
import com.symmetricalpalmtree.notesproutsn.crypto.KeyMaterial
import com.symmetricalpalmtree.notesproutsn.crypto.KeySession
import com.symmetricalpalmtree.notesproutsn.crypto.PassphraseStore
import com.symmetricalpalmtree.notesproutsn.data.index.IndexRepository
import com.symmetricalpalmtree.notesproutsn.databinding.ActivityEncryptionBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * **Encryption** (arc 26 / U1) — the one screen about the secret that opens the library. Until this
 * arc the recovery key was shown once at first launch and never again in a release build; this
 * screen is where a person can see it again, replace it (U3) and drop it from this device.
 *
 *  - **Status.** Whether a recovery key is cached here, and how many notebooks open under it. The
 *    count reads the index's `keyScope` column, so it is honest from U4 on and "every notebook"
 *    until then.
 *  - **Reveal recovery key…** — the key in monospace with Copy / Close, og's wording. No
 *    re-authentication (decision 7): the device PIN is the gate, exactly as og.
 *  - **Change passphrase…** — U3. The control is GONE until it exists.
 *  - **Forget on this device…** — confirm, then the cached passphrase, the RAM copy and every cached
 *    raw key leave this device and the app closes. Nothing is decrypted or modified; the next launch
 *    is the Unlock screen (decision 6 — ships in release).
 *
 * Forget ends by **killing the process**, not just `finishAffinity`: [com.symmetricalpalmtree.notesproutsn.data.index.SnIndex]
 * has no close, and a relaunch into a live process would find the index open and answer READY
 * with no key cached anywhere — the debug item this screen replaces did the same for the same
 * reason. The passphrase is never logged and never rides an Intent; the only place it goes from
 * here is the clipboard, on the person's own tap.
 */
class EncryptionActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEncryptionBinding
    private val repository by lazy { IndexRepository() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!IndexGuard.ready(this)) return
        binding = ActivityEncryptionBinding.inflate(layoutInflater)
        setContentView(binding.root)
        TopGuard.applyInsetPadding(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        TooltipCompat.setTooltipText(binding.btnBack, binding.btnBack.contentDescription)
        binding.btnReveal.setOnClickListener { reveal() }
        binding.btnForget.setOnClickListener { confirmForget() }
    }

    override fun onResume() {
        super.onResume()
        renderStatus()
    }

    /** The two status lines. Off Main: the key is a Keystore read and the count is a query. */
    private fun renderStatus() {
        lifecycleScope.launch {
            val isSet = withContext(Dispatchers.IO) { PassphraseStore.getGlobalPassphrase(this@EncryptionActivity) != null }
            val count = repository.countGlobalNotebooks()
            binding.keyStatus.setText(if (isSet) R.string.encryption_key_set else R.string.encryption_key_not_set)
            binding.keyCount.text = when (count) {
                0 -> getString(R.string.encryption_count_zero)
                1 -> getString(R.string.encryption_count_one)
                else -> getString(R.string.encryption_count_many, count)
            }
        }
    }

    // ── Reveal ───────────────────────────────────────────────────────────────

    private fun reveal() {
        lifecycleScope.launch {
            val key = withContext(Dispatchers.IO) { PassphraseStore.getGlobalPassphrase(this@EncryptionActivity) }
            if (key == null) {
                // Cannot happen behind IndexGuard (the index opened under a cached key) short of a
                // Keystore wipe under a live process — a dialog, because on e-ink a toast is missable.
                Dialogs.problem(this@EncryptionActivity, R.string.encryption_reveal_none_title, R.string.encryption_reveal_none_body)
                return@launch
            }
            val keyView = TextView(this@EncryptionActivity).apply {
                text = key
                setTextIsSelectable(true)
                typeface = Typeface.MONOSPACE
                textSize = 16f
                letterSpacing = 0.02f
                setTextColor(ContextCompat.getColor(context, R.color.inkBlack))
                // Inside the dialog's own message inset, so the key lines up with the body text.
                val d = resources.displayMetrics.density
                setPadding((24 * d).toInt(), (16 * d).toInt(), (24 * d).toInt(), (4 * d).toInt())
            }
            Dialogs.style(
                AlertDialog.Builder(this@EncryptionActivity)
                    .setTitle(R.string.encryption_reveal_title)
                    .setMessage(R.string.encryption_reveal_body)
                    .setView(keyView)
                    .setPositiveButton(R.string.recovery_copy) { _, _ -> copyToClipboard(key) }
                    .setNegativeButton(R.string.encryption_close, null)
                    .create()
            ).show()
        }
    }

    private fun copyToClipboard(key: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(getString(R.string.recovery_clip_label), key))
        // A toast: it only confirms something that already happened.
        Toast.makeText(this, R.string.recovery_copied, Toast.LENGTH_SHORT).show()
    }

    // ── Forget ───────────────────────────────────────────────────────────────

    private fun confirmForget() {
        Dialogs.style(
            AlertDialog.Builder(this)
                .setTitle(R.string.encryption_forget_title)
                .setMessage(R.string.encryption_forget_body)
                .setPositiveButton(R.string.encryption_forget_confirm) { _, _ -> forget() }
                .setNegativeButton(R.string.cancel, null)
                .create()
        ).show()
    }

    /**
     * Drop every copy of the key this device holds — the Keystore-backed cache, the process-RAM
     * copy, and every cached raw key — then close. Nothing on disk changes: every `.soil`, every
     * extension store and the index stay exactly as they are, and the next launch is Unlock.
     */
    private fun forget() {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                PassphraseStore.clearGlobalPassphrase(this@EncryptionActivity)
                KeyMaterial.clearAll(this@EncryptionActivity)
                KeySession.clear()
            }
            Slog.d(TAG) { "recovery key forgotten on this device; closing" }
            finishAffinity()
            // The index is still open in this process, and SnIndex has no close: a relaunch of the
            // same process would answer READY. End the process so the next launch really boots.
            Handler(Looper.getMainLooper()).postDelayed({
                android.os.Process.killProcess(android.os.Process.myPid())
            }, KILL_DELAY_MS)
        }
    }

    companion object {
        private const val TAG = "Encryption"
        private const val KILL_DELAY_MS = 400L

        fun intent(context: Context) = Intent(context, EncryptionActivity::class.java)
    }
}
