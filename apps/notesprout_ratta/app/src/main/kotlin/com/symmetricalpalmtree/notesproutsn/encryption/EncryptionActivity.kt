package com.symmetricalpalmtree.notesproutsn.encryption

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
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
import com.symmetricalpalmtree.notesproutsn.bootstrap.BootstrapActivity
import com.symmetricalpalmtree.notesproutsn.crypto.GlobalKey
import com.symmetricalpalmtree.notesproutsn.crypto.GlobalRotation
import com.symmetricalpalmtree.notesproutsn.crypto.KeyMaterial
import com.symmetricalpalmtree.notesproutsn.crypto.KeySession
import com.symmetricalpalmtree.notesproutsn.crypto.PassphraseCache
import com.symmetricalpalmtree.notesproutsn.crypto.NotebookUnlocks
import com.symmetricalpalmtree.notesproutsn.crypto.PassphraseRules
import com.symmetricalpalmtree.notesproutsn.crypto.PassphraseStore
import com.symmetricalpalmtree.notesproutsn.data.index.IndexRepository
import com.symmetricalpalmtree.notesproutsn.data.index.SnIndex
import com.symmetricalpalmtree.notesproutsn.databinding.ActivityEncryptionBinding
import com.symmetricalpalmtree.notesproutsn.databinding.DialogPassphraseCurrentBinding
import com.symmetricalpalmtree.notesproutsn.databinding.DialogPassphraseNewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

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
 *  - **Change passphrase…** (U3) — the whole library's key, replaced in place. Verify the current
 *    key by string match against the cache (the recovery key's confusable fold is accepted, since
 *    the field is where a hand-transcribed key lands), then *New passphrase* — **Generate a new
 *    recovery key** (the default) or **Choose my own** under [com.symmetricalpalmtree.notesproutsn.crypto.PassphraseRules] —
 *    then a confirm dialog carrying the backups warning (decision 4: every existing backup opens
 *    only with the old key until the next run replaces it). [com.symmetricalpalmtree.notesproutsn.crypto.GlobalRotation]
 *    then re-keys every notebook, the extension stores and the index last, under a non-cancelable
 *    progress dialog whose Cancel stops **after** the file in hand and leaves the rest to the
 *    banner's Resume. **Once the engine has closed the index this screen touches nothing but
 *    dialogs** — no index read, no status render — and the only way off it is
 *    `BootstrapActivity.relaunchIntent` + `finishAffinity()`, which every terminal outcome except
 *    Cancel takes (a cancelled rotation stops before the index's own turn, so that one stays).
 *  - **Resume** — while a rotation marker exists the banner replaces Change passphrase and Forget
 *    (both GONE): neither is a safe thing to start on top of a library that is in two keys.
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
        binding.btnChange.setOnClickListener { changePassphrase() }
        binding.btnResume.setOnClickListener { runRotation(resume = true) }
        binding.btnForget.setOnClickListener { confirmForget() }
    }

    override fun onResume() {
        super.onResume()
        renderStatus()
    }

    /**
     * The two status lines and the resume banner. Off Main: the key and the marker are Keystore
     * reads and the count is a query. Never called once the rotation has closed the index.
     */
    private fun renderStatus() {
        // A Home-and-back while the failure dialog waits for its OK: the index is closed and the
        // screen is on its way out — a count query now would throw.
        if (!SnIndex.isReady()) return
        lifecycleScope.launch {
            val (isSet, hasMarker) = withContext(Dispatchers.IO) {
                Pair(
                    PassphraseStore.getGlobalPassphrase(this@EncryptionActivity) != null,
                    GlobalRotation.hasMarker(this@EncryptionActivity),
                )
            }
            val count = repository.countGlobalNotebooks()
            binding.keyStatus.setText(if (isSet) R.string.encryption_key_set else R.string.encryption_key_not_set)
            binding.keyCount.text = when (count) {
                0 -> getString(R.string.encryption_count_zero)
                1 -> getString(R.string.encryption_count_one)
                else -> getString(R.string.encryption_count_many, count)
            }
            // While a change is half-done the only offer is to finish it.
            binding.resumeBanner.visibility = if (hasMarker) View.VISIBLE else View.GONE
            binding.btnChange.visibility = if (hasMarker) View.GONE else View.VISIBLE
            binding.btnForget.visibility = if (hasMarker) View.GONE else View.VISIBLE
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

    // ── Change passphrase ────────────────────────────────────────────────────

    /** Step 1: prove the current key. A string match against the cache — no file is touched yet. */
    private fun changePassphrase() {
        lifecycleScope.launch {
            val current = withContext(Dispatchers.IO) { PassphraseStore.getGlobalPassphrase(this@EncryptionActivity) }
            if (current == null) {
                Dialogs.problem(this@EncryptionActivity, R.string.encryption_reveal_none_title, R.string.encryption_reveal_none_body)
                return@launch
            }
            askCurrent(current)
        }
    }

    /**
     * "Current passphrase". The positive button is wired **after** `show()` so a wrong entry keeps
     * the dialog (and the typing) — the default listener dismisses before anything can object. The
     * IME is never touched: on Ratta a hardware keyboard only delivers keys while it is shown.
     */
    private fun askCurrent(current: String) {
        if (isFinishing || isDestroyed) return
        val view = DialogPassphraseCurrentBinding.inflate(layoutInflater)
        val dialog = Dialogs.style(
            AlertDialog.Builder(this)
                .setTitle(R.string.encryption_current_title)
                .setMessage(R.string.encryption_current_body)
                .setView(view.root)
                .setPositiveButton(R.string.encryption_continue, null)
                .setNegativeButton(R.string.cancel, null)
                .create()
        )
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val typed = view.field.text.toString().trim()
            // The fold rescues a hand-transcribed recovery key (O for 0, l for 1); a typed
            // passphrase matches plainly. Neither branch echoes anything anywhere.
            if (typed == current || GlobalKey.normalize(typed) == current) {
                dialog.dismiss()
                askNew(current)
            } else {
                view.error.visibility = View.VISIBLE
            }
        }
    }

    /** Step 2: generate a fresh recovery key, or type one under [PassphraseRules]. */
    private fun askNew(current: String) {
        if (isFinishing || isDestroyed) return
        val view = DialogPassphraseNewBinding.inflate(layoutInflater)
        view.modeGroup.setOnCheckedChangeListener { _, checked ->
            view.ownFields.visibility = if (checked == R.id.modeChoose) View.VISIBLE else View.GONE
            view.error.visibility = View.GONE
        }
        val dialog = Dialogs.style(
            AlertDialog.Builder(this)
                .setTitle(R.string.encryption_new_title)
                .setView(view.root)
                .setPositiveButton(R.string.encryption_continue, null)
                .setNegativeButton(R.string.cancel, null)
                .create()
        )
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (view.modeGenerate.isChecked) {
                dialog.dismiss()
                confirmChange(GlobalKey.mint(), minted = true)
                return@setOnClickListener
            }
            val typed = view.newField.text.toString()
            when (val verdict = PassphraseRules.check(typed, view.confirmField.text.toString(), current)) {
                PassphraseRules.Verdict.OK -> {
                    dialog.dismiss()
                    confirmChange(PassphraseRules.normalize(typed), minted = false)
                }
                else -> {
                    view.error.setText(
                        when (verdict) {
                            PassphraseRules.Verdict.TOO_SHORT -> R.string.encryption_rule_short
                            PassphraseRules.Verdict.MISMATCH -> R.string.encryption_rule_mismatch
                            else -> R.string.encryption_rule_same
                        }
                    )
                    view.error.visibility = View.VISIBLE
                }
            }
        }
    }

    /** Step 3: the backups warning (decision 4) — the one consequence that outlives the rotation. */
    private fun confirmChange(newPassphrase: String, minted: Boolean) {
        if (isFinishing || isDestroyed) return
        val body = StringBuilder(getString(R.string.encryption_change_body))
        if (minted) body.append("\n\n").append(getString(R.string.encryption_change_minted))
        Dialogs.style(
            AlertDialog.Builder(this)
                .setTitle(R.string.encryption_change_title)
                .setMessage(body.toString())
                .setPositiveButton(R.string.encryption_change_confirm) { _, _ ->
                    runRotation(resume = false, newPassphrase = newPassphrase, minted = minted)
                }
                .setNegativeButton(R.string.cancel, null)
                .create()
        ).show()
    }

    /**
     * The rotation itself. The screen stays on for the duration (a rekey is two KDF verifies plus a
     * copy per file — minutes over a real library), the progress dialog is not cancelable, and its
     * Cancel only asks: [GlobalRotation] finishes the file in hand and leaves the rest to the
     * marker. Every outcome but *Cancelled* leaves through Bootstrap, because by then the index has
     * been closed for its own rekey and nothing on this screen may read a row again.
     */
    private fun runRotation(resume: Boolean, newPassphrase: String? = null, minted: Boolean = false) {
        val cancel = AtomicBoolean(false)
        var stopping = false
        var progress: GlobalRotation.Progress? = null

        val dialog = Dialogs.style(
            AlertDialog.Builder(this)
                .setTitle(R.string.encryption_progress_title)
                .setMessage(getString(R.string.encryption_progress_keep_open))
                .setNegativeButton(R.string.cancel, null)
                .setCancelable(false)
                .create()
        )
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
            // A tapped Cancel that looks dead is worse than none on e-ink: the last line changes
            // and the button leaves. The dialog itself stays until the file in hand is finished.
            cancel.set(true)
            stopping = true
            progress?.let { p -> dialog.setMessage(progressText(p, stopping = true)) }
            it.visibility = View.GONE
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        lifecycleScope.launch {
            val onProgress: suspend (GlobalRotation.Progress) -> Unit = { p ->
                withContext(Dispatchers.Main) {
                    progress = p
                    dialog.setMessage(progressText(p, stopping))
                }
            }
            try {
                val result = if (resume) {
                    GlobalRotation.resume(this@EncryptionActivity, onProgress, cancel)
                } else {
                    GlobalRotation.start(this@EncryptionActivity, newPassphrase!!, minted, onProgress, cancel)
                }
                Slog.d(TAG) { "rotation result: ${result::class.simpleName}" }
                if (isFinishing || isDestroyed) return@launch
                dialog.dismiss()
                when (result) {
                    is GlobalRotation.Result.Complete -> showComplete(result)
                    is GlobalRotation.Result.Cancelled -> showCancelled(result)
                    is GlobalRotation.Result.Failed -> showFailed(result)
                }
            } finally {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    /** Count, then what is being re-keyed, then the one instruction. */
    private fun progressText(p: GlobalRotation.Progress, stopping: Boolean): String {
        val label = when (val l = p.label) {
            is GlobalRotation.Label.Notebook ->
                l.name.ifBlank { getString(R.string.encryption_progress_notebook) }
            GlobalRotation.Label.Stores -> getString(R.string.encryption_progress_stores)
            GlobalRotation.Label.Index -> getString(R.string.encryption_progress_index)
        }
        val tail = getString(
            if (stopping) R.string.encryption_progress_stopping else R.string.encryption_progress_keep_open
        )
        return getString(R.string.encryption_progress_count, p.done + 1, p.total) + "\n" + label + "\n\n" + tail
    }

    private fun showComplete(result: GlobalRotation.Result.Complete) {
        val body = StringBuilder(
            when (result.notebooks) {
                0 -> getString(R.string.encryption_done_zero)
                1 -> getString(R.string.encryption_done_one)
                else -> getString(R.string.encryption_done_many, result.notebooks)
            }
        )
        if (result.quarantined > 0) {
            body.append("\n\n").append(
                if (result.quarantined == 1) getString(R.string.encryption_done_quarantined_one)
                else getString(R.string.encryption_done_quarantined_many, result.quarantined)
            )
        }
        Dialogs.style(
            AlertDialog.Builder(this)
                .setTitle(R.string.encryption_done_title)
                .setMessage(body.toString())
                .setPositiveButton(R.string.encryption_done_backup) { _, _ -> relaunch(thenBackup = true) }
                .setNegativeButton(R.string.encryption_done_finish) { _, _ -> relaunch(thenBackup = false) }
                .setCancelable(false)
                .create()
        ).show()
    }

    /** Cancel is honoured only before the index's own turn, so the index is still open here: the
     *  screen simply re-renders and the banner takes over. */
    private fun showCancelled(result: GlobalRotation.Result.Cancelled) {
        Dialogs.confirm(
            this,
            getString(R.string.encryption_paused_title),
            if (result.remaining == 1) getString(R.string.encryption_paused_one)
            else getString(R.string.encryption_paused_many, result.remaining),
        )
        renderStatus()
    }

    /** A failure may have come after the index was closed, so the only safe exit is Bootstrap. */
    private fun showFailed(result: GlobalRotation.Result.Failed) {
        val body = getString(
            when (result.reason) {
                GlobalRotation.Reason.NO_CACHED_GLOBAL -> R.string.encryption_failed_no_key
                GlobalRotation.Reason.TRANSIENT -> R.string.encryption_failed_transient
                GlobalRotation.Reason.STUCK -> R.string.encryption_failed_stuck
            }
        )
        Dialogs.confirm(this, getString(R.string.encryption_failed_title), body) { relaunch(thenBackup = false) }
    }

    /** The one way off this screen once the rotation has run: a clean task rooted at Bootstrap. */
    private fun relaunch(thenBackup: Boolean) {
        startActivity(BootstrapActivity.relaunchIntent(this, thenBackup))
        finishAffinity()
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
                PassphraseCache.clear()
                NotebookUnlocks.clear()
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
