package com.symmetricalpalmtree.notesprout.extension

import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.notesprout.R
import com.symmetricalpalmtree.notesprout.core.Connectivity
import com.symmetricalpalmtree.notesprout.core.Dialogs
import com.symmetricalpalmtree.notesprout.core.Slog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The recognizer model-consent flow (arc 3 / M1–M2, promoted from the debug menu to main source in
 * arc 4 / H3 so the heading action can use it): make the one recognizer READY, asking the user first
 * when a download is needed, then run [onReady] — or [onGaveUp] when the flow ended without it.
 *
 * `status()` → READY → [onReady] · NEEDS_DOWNLOAD → "Recognition model needed" dialog (offline
 * pre-check first: `Connectivity.isOnline` → an offline dialog instead of Download; nothing downloads
 * before Download — the extension's `prepare()` is the only thing that starts one) → `prepare()` +
 * a progress dialog with an elapsed counter that polls `status()` every [POLL_MS] until READY →
 * [onReady] with no second tap · DOWNLOADING (a download already in flight — consent was given) →
 * straight to the progress dialog · UNAVAILABLE → problem dialog. Give-up rules: [MAX_POLL_FAILURES]
 * consecutive failed polls, [OFFLINE_GIVE_UP_MS] offline mid-download, or [DOWNLOAD_CAP_S] elapsed →
 * "Download failed"; Cancel hides the dialog only (the download keeps running in the extension).
 *
 * Every dialog is the core's; the extension shows nothing. One flow at a time is the **caller's**
 * concern (the debug menu keeps its busy guard; the notebook's H action has its own in H4). Nothing
 * here touches ink or text.
 */
object RecognizerReadiness {

    private const val TAG = "RecognizerReadiness"
    /** Progress-dialog poll period (also its e-ink refresh cadence) and the give-up cap. */
    const val POLL_MS = 2_000L
    const val DOWNLOAD_CAP_S = 300
    const val OFFLINE_GIVE_UP_MS = 30_000L
    /** Consecutive failed `status()` polls tolerated before the download is declared failed (M2). */
    const val MAX_POLL_FAILURES = 5

    /**
     * Drive the flow on [activity]'s lifecycle scope. [onReady] runs on Main once the recognizer is
     * READY (possibly at once); [onGaveUp] runs on Main on every other exit (dialog cancelled, download
     * failed, engine unavailable, status call failed) — exactly one of the two is called.
     * [problemTitleRes] titles the failure dialogs.
     */
    fun ensureReady(
        activity: AppCompatActivity,
        client: RecognizerClient,
        onReady: suspend () -> Unit,
        onGaveUp: () -> Unit,
        @StringRes problemTitleRes: Int = R.string.recognize_problem_title,
    ) {
        activity.lifecycleScope.launch {
            val status = try {
                client.status()
            } catch (e: ExtensionCallException) {
                Slog.d(TAG) { "status failed: ${e.javaClass.simpleName}: ${e.message}" }
                problem(activity, problemTitleRes, R.string.recognize_failed)
                onGaveUp(); return@launch
            }
            when (status) {
                RecognizerStatus.READY -> onReady()
                RecognizerStatus.UNAVAILABLE -> { problem(activity, problemTitleRes, R.string.recognize_unavailable); onGaveUp() }
                RecognizerStatus.DOWNLOADING -> awaitDownload(activity, client, prepare = false, onReady, onGaveUp)
                else -> promptDownload(activity, client, problemTitleRes, onReady, onGaveUp)   // NEEDS_DOWNLOAD
            }
        }
    }

    /** "Recognition model needed" — Download / Cancel (or an offline notice with OK). */
    private fun promptDownload(
        activity: AppCompatActivity, client: RecognizerClient, @StringRes problemTitleRes: Int,
        onReady: suspend () -> Unit, onGaveUp: () -> Unit,
    ) {
        if (activity.isFinishing || activity.isDestroyed) { onGaveUp(); return }
        // Pre-flight: ML Kit's downloader hangs rather than fails when offline (M1: no error after a
        // minute on the Nomad), so the core checks first and says so instead of offering Download.
        val online = Connectivity.isOnline(activity)
        val builder = AlertDialog.Builder(activity)
            .setTitle(R.string.recognize_model_needed_title)
            .setOnCancelListener { onGaveUp() }
        if (online) {
            builder.setMessage(R.string.recognize_model_needed_body)
                .setPositiveButton(R.string.recognize_download) { _, _ -> awaitDownload(activity, client, prepare = true, onReady, onGaveUp) }
                .setNegativeButton(R.string.cancel) { _, _ -> onGaveUp() }
        } else {
            builder.setMessage(R.string.recognize_model_needed_offline_body)
                .setPositiveButton(R.string.ok) { _, _ -> onGaveUp() }
        }
        Dialogs.style(builder.create()).show()
    }

    /**
     * Optionally `prepare()`, then a progress dialog (elapsed-time counter — the e-ink-safe
     * indeterminate indicator, refreshed every [POLL_MS]) that polls `status()` until READY →
     * [onReady]; a chain failure or a cap → "Download failed" → [onGaveUp]. Cancel hides the dialog
     * only — the download itself keeps running in the extension, so the next attempt finds it further
     * along or done.
     */
    private fun awaitDownload(
        activity: AppCompatActivity, client: RecognizerClient, prepare: Boolean,
        onReady: suspend () -> Unit, onGaveUp: () -> Unit,
    ) {
        activity.lifecycleScope.launch {
            var cancelled = false
            var done = false
            val progress = AlertDialog.Builder(activity)
                .setTitle(R.string.recognize_downloading_title)
                .setMessage(activity.getString(R.string.recognize_downloading_body, 0))
                .setNegativeButton(R.string.cancel) { _, _ -> cancelled = true }
                .setOnCancelListener { cancelled = true }
                .create()
            Dialogs.style(progress)
            suspend fun ready() { done = true; progress.dismiss(); onReady() }
            fun failed() { done = true; if (progress.isShowing) progress.dismiss(); showDownloadFailed(activity); onGaveUp() }
            try {
                if (prepare) {
                    try {
                        client.prepare()
                    } catch (e: ExtensionCallException) {
                        Slog.d(TAG) { "prepare failed: ${e.javaClass.simpleName}: ${e.message}" }
                        failed(); return@launch
                    }
                }
                if (activity.isFinishing || activity.isDestroyed) { done = true; onGaveUp(); return@launch }
                progress.show()
                val t0 = System.currentTimeMillis()
                var offlineSinceMs = -1L
                var pollFailures = 0
                while (!cancelled) {
                    delay(POLL_MS)
                    if (cancelled || activity.isFinishing || activity.isDestroyed) break
                    val now = System.currentTimeMillis()
                    val elapsedS = ((now - t0) / 1000L).toInt()
                    // The network dropping mid-download: ML Kit does not fail, it waits. Say so, and give
                    // up after OFFLINE_GIVE_UP_MS offline rather than after the 5-minute cap.
                    if (!Connectivity.isOnline(activity)) {
                        if (offlineSinceMs < 0) offlineSinceMs = now
                        if (now - offlineSinceMs >= OFFLINE_GIVE_UP_MS) {
                            Slog.d(TAG) { "offline for ${(now - offlineSinceMs) / 1000} s during download — giving up" }
                            failed(); return@launch
                        }
                        progress.setMessage(activity.getString(R.string.recognize_downloading_offline_body, elapsedS))
                        continue
                    }
                    offlineSinceMs = -1L
                    // A failed poll (bind refused / died / timed out) is tolerated as transient a few times;
                    // an extension that stays unbindable (BOOX re-disabling it, a crash on start) is a
                    // failure the host knows now — not after the 5-minute cap.
                    val status = try {
                        client.status().also { pollFailures = 0 }
                    } catch (e: ExtensionCallException) {
                        pollFailures++
                        Slog.d(TAG) { "status poll failed ($pollFailures): ${e.message}" }
                        if (pollFailures >= MAX_POLL_FAILURES) { failed(); return@launch }
                        RecognizerStatus.DOWNLOADING
                    }
                    when (status) {
                        RecognizerStatus.READY -> {
                            Slog.d(TAG) { "model ready after $elapsedS s" }
                            ready(); return@launch
                        }
                        RecognizerStatus.DOWNLOADING -> {
                            progress.setMessage(activity.getString(R.string.recognize_downloading_body, elapsedS))
                            if (elapsedS >= DOWNLOAD_CAP_S) { failed(); return@launch }
                        }
                        else -> {   // NEEDS_DOWNLOAD after prepare() = the chain failed; UNAVAILABLE = engine gone
                            Slog.d(TAG) { "download failed (status $status) after $elapsedS s" }
                            failed(); return@launch
                        }
                    }
                }
                Slog.d(TAG) { "download dialog cancelled — download continues in the extension" }
            } finally {
                if (progress.isShowing) progress.dismiss()
                if (!done) onGaveUp()   // cancelled / activity gone
            }
        }
    }

    private fun showDownloadFailed(activity: AppCompatActivity) {
        if (activity.isFinishing || activity.isDestroyed) return
        Dialogs.style(
            AlertDialog.Builder(activity)
                .setTitle(R.string.recognize_download_failed_title)
                .setMessage(R.string.recognize_download_failed_body)
                .setPositiveButton(R.string.ok, null)
                .create()
        ).show()
    }

    private fun problem(activity: AppCompatActivity, @StringRes titleRes: Int, @StringRes messageRes: Int) =
        Dialogs.problem(activity, titleRes, messageRes)
}
