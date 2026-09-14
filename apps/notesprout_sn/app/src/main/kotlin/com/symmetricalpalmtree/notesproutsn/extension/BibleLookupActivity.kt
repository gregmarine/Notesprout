package com.symmetricalpalmtree.notesproutsn.extension

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.FrameLayout
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.notesproutsn.core.OpeningOverlay
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.data.index.SnIndex
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

/**
 * The host's **lookup screen** (arc 39 "Lookup") — a translucent trampoline the document editor
 * starts for a result once the notebook has answered `REFERENCE_OPENED`. It has no chrome of its
 * own but the "Opening…" box: it [LookupHandoff.take]s the reference the notebook parked, opens the
 * reader's showing (`BibleClient.open` on the passage — the lease, the held bind, `beginAt`),
 * launches the reader for a result, and when the reader returns it finishes the bind and itself,
 * landing the user back on the editor exactly as they left it.
 *
 * **Why a screen at all.** The reader admits only the host as its caller, and the notebook — the
 * host screen that *has* a Bible door — is stopped behind the editor, where a child's result never
 * reaches it until the editor closes (measured on the Nomad: the second lookup answered "already
 * showing"). A live host Activity is what gets the result the moment the reader closes, so the
 * bind is released and the next lookup is free.
 *
 * **Exported, and gated three ways**: it does nothing unless the index is open (a cold host has
 * parked nothing), unless it was started *for a result* by the package the park names (the editor
 * the notebook holds a bind to — `callingPackage`, the reader's own check mirrored), and unless
 * that park is fresh. Any refusal is a silent finish. Nothing rides its Intent, in or out — the
 * one result code it can add, [DocumentContract.RESULT_LOOKUP_FAILED], is the editor's cue to
 * explain that the reader could not be opened. No Send: the editor, not a page, is behind the
 * reader. Not on the surface stack: a cold launch puts the editor back, not a reading on top of it.
 *
 * Never logs the reference.
 */
class BibleLookupActivity : AppCompatActivity() {

    private var client: BibleClient? = null
    private var launched = false

    private val launcher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result -> onResult(result) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A recreate (never expected — the manifest pins the config) has no park to take: leave.
        if (savedInstanceState != null) { finish(); return }
        // Not `IndexGuard.ready(this)`: that bounces the whole task through the bootstrap
        // screen, and this is a result trampoline the editor is waiting on — a closed index is
        // answered as a failed lookup (the editor's "unavailable" alert), never a task restart.
        if (!SnIndex.isReady()) { Slog.d(TAG) { "refused: index not open" }; finish(); return }
        val parked = LookupHandoff.take(callingPackage)
        if (parked == null) {
            Slog.d(TAG) { "refused: nothing parked for ${callingPackage ?: "(no caller)"}" }
            finish()
            return
        }
        setContentView(FrameLayout(this))
        // This screen is front-most, so the box draws — the whole reason the wait lives here and
        // not on the stopped notebook. The launch runs behind its frame.
        OpeningOverlay.showThen(this) { open(parked) }
    }

    private fun open(parked: LookupHandoff.Parked) {
        lifecycleScope.launch {
            val fresh = BibleClient(this@BibleLookupActivity, parked.reader)
            client = fresh
            val intent = fresh.open(parked.wire, sendEnabled = false)
            if (isFinishing || isDestroyed) { client = null; fresh.finish(); return@launch }
            if (intent == null) { fail(fresh); return@launch }
            val outcome = ScreenLaunch.attempt(
                resolves = { intent.resolveActivity(packageManager) != null },
                beforeLaunch = {},
                launch = { launcher.launch(intent) },
                afterFailure = {},
            )
            if (outcome != ScreenLaunch.Outcome.Launched) { fail(fresh); return@launch }
            launched = true
            Slog.d(TAG) { "reader launched over the editor" }
        }
    }

    private suspend fun fail(fresh: BibleClient) {
        client = null
        fresh.finish()
        Slog.d(TAG) { "open failed — the editor explains" }
        setResult(DocumentContract.RESULT_LOOKUP_FAILED)
        finish()
    }

    /** The reader is gone: release the bind on a detached scope (this screen is leaving too). */
    private fun onResult(result: ActivityResult) {
        Slog.d(TAG) { "reader returned: resultCode=${result.resultCode}" }
        val open = client
        client = null
        if (open != null) MainScope().launch { open.finish() }
        setResult(Activity.RESULT_OK)
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        // The backstop: a bind must not outlive the screen that opened it.
        val open = client ?: return
        client = null
        MainScope().launch { open.finish() }
    }

    private companion object {
        const val TAG = "BibleLookupActivity"
    }
}
