package com.symmetricalpalmtree.notesproutsn.extension

import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.symmetricalpalmtree.notesproutsn.R
import com.symmetricalpalmtree.notesproutsn.core.Connectivity
import com.symmetricalpalmtree.notesproutsn.core.Dialogs
import com.symmetricalpalmtree.notesproutsn.core.Slog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The model-consent flow: make the one recognizer READY — asking the user first when that needs a
 * download — then run [onReady], or [onGaveUp] when the flow ended without it. **Exactly one of the
 * two runs**, always on Main.
 *
 * `status()` → READY → [onReady] · NEEDS_DOWNLOAD → the one-time "Recognition model needed" dialog
 * (offline pre-check first: ML Kit's downloader *hangs* rather than fails with no network, so
 * offering Download offline would look broken — [Connectivity] decides, and nothing downloads before
 * the Download tap, because the extension's `prepare()` is the only thing that may start one) →
 * `prepare()` and a progress dialog that polls `status()` every [POLL_MS] until READY → [onReady]
 * with no second tap · DOWNLOADING (something is already in flight — consent was given earlier) →
 * straight to the progress dialog · UNAVAILABLE → a problem dialog.
 *
 * The progress dialog's indicator is an **elapsed-seconds counter**, not a spinner: on e-ink an
 * animation is a stream of full refreshes, while a number that changes every two seconds reads as
 * "still working" for the price of one. Give-up rules: [MAX_POLL_FAILURES] consecutive failed polls
 * (an extension that stays unbindable is a failure the user should hear about now, not in five
 * minutes), [OFFLINE_GIVE_UP_MS] offline mid-download, or the [DOWNLOAD_CAP_S] cap. **Cancel hides
 * the dialog only** — the download keeps running inside the extension, so the next attempt finds it
 * further along or finished.
 *
 * Every dialog belongs to the host; the extension shows nothing. One flow at a time is the caller's
 * concern. Nothing here touches ink or text.
 */
object RecognizerReadiness {

    private const val TAG = "RecognizerReadiness"

    /** Poll period, and with it the progress dialog's e-ink refresh cadence. */
    const val POLL_MS = 2_000L

    /** The whole download may take this long before it is declared failed. */
    const val DOWNLOAD_CAP_S = 300

    /** Offline this long mid-download → failed, rather than waiting out the cap. */
    const val OFFLINE_GIVE_UP_MS = 30_000L

    /** Consecutive failed `status()` polls tolerated as transient. */
    const val MAX_POLL_FAILURES = 5

    /**
     * Drive the flow on [activity]'s lifecycle scope. [problemTitleRes] titles the failure dialogs.
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
                Dialogs.problem(activity, problemTitleRes, R.string.recognize_failed)
                onGaveUp()
                return@launch
            }
            when (status) {
                RecognizerStatus.READY -> onReady()
                RecognizerStatus.UNAVAILABLE -> {
                    Dialogs.problem(activity, problemTitleRes, R.string.recognize_unavailable)
                    onGaveUp()
                }
                RecognizerStatus.DOWNLOADING -> awaitDownload(activity, client, prepare = false, onReady, onGaveUp)
                else -> promptDownload(activity, client, onReady, onGaveUp)   // NEEDS_DOWNLOAD
            }
        }
    }

    /** "Recognition model needed" — Download / Cancel, or an offline notice with only OK. */
    private fun promptDownload(
        activity: AppCompatActivity,
        client: RecognizerClient,
        onReady: suspend () -> Unit,
        onGaveUp: () -> Unit,
    ) {
        if (activity.isFinishing || activity.isDestroyed) { onGaveUp(); return }
        val builder = AlertDialog.Builder(activity)
            .setTitle(R.string.recognize_model_needed_title)
            .setOnCancelListener { onGaveUp() }
        if (Connectivity.isOnline(activity)) {
            builder.setMessage(R.string.recognize_model_needed_body)
                .setPositiveButton(R.string.recognize_download) { _, _ ->
                    awaitDownload(activity, client, prepare = true, onReady, onGaveUp)
                }
                .setNegativeButton(R.string.cancel) { _, _ -> onGaveUp() }
        } else {
            builder.setMessage(R.string.recognize_model_needed_offline_body)
                .setPositiveButton(R.string.ok) { _, _ -> onGaveUp() }
        }
        Dialogs.style(builder.create()).show()
    }

    /**
     * Optionally `prepare()`, then the progress dialog, polling `status()` until READY → [onReady];
     * a chain failure or a give-up rule → "Download failed" → [onGaveUp]. Cancel takes the dialog
     * down and nothing else.
     */
    private fun awaitDownload(
        activity: AppCompatActivity,
        client: RecognizerClient,
        prepare: Boolean,
        onReady: suspend () -> Unit,
        onGaveUp: () -> Unit,
    ) {
        activity.lifecycleScope.launch {
            var cancelled = false
            var settled = false
            val progress = AlertDialog.Builder(activity)
                .setTitle(R.string.recognize_downloading_title)
                .setMessage(activity.getString(R.string.recognize_downloading_body, 0))
                .setNegativeButton(R.string.cancel) { _, _ -> cancelled = true }
                .setOnCancelListener { cancelled = true }
                .create()
            Dialogs.style(progress)

            suspend fun succeed() { settled = true; progress.dismiss(); onReady() }
            fun fail() {
                settled = true
                if (progress.isShowing) progress.dismiss()
                showDownloadFailed(activity)
                onGaveUp()
            }

            try {
                if (prepare) {
                    try {
                        client.prepare()
                    } catch (e: ExtensionCallException) {
                        Slog.d(TAG) { "prepare failed: ${e.javaClass.simpleName}: ${e.message}" }
                        fail(); return@launch
                    }
                }
                if (activity.isFinishing || activity.isDestroyed) { settled = true; onGaveUp(); return@launch }
                progress.show()

                val t0 = System.currentTimeMillis()
                var offlineSinceMs = -1L
                var pollFailures = 0
                while (!cancelled) {
                    delay(POLL_MS)
                    if (cancelled || activity.isFinishing || activity.isDestroyed) break
                    val now = System.currentTimeMillis()
                    val elapsedS = ((now - t0) / 1000L).toInt()

                    // status() is a purely LOCAL service bind — it works with no network, so poll
                    // it every iteration, offline included: a model that finished downloading just
                    // as connectivity dropped must still be seen as READY, not falsely failed on
                    // the offline clock.
                    val status = try {
                        client.status().also { pollFailures = 0 }
                    } catch (e: ExtensionCallException) {
                        pollFailures++
                        Slog.d(TAG) { "status poll failed ($pollFailures): ${e.message}" }
                        if (pollFailures >= MAX_POLL_FAILURES) { fail(); return@launch }
                        RecognizerStatus.DOWNLOADING
                    }
                    if (status == RecognizerStatus.READY) {
                        Slog.d(TAG) { "model ready after $elapsedS s" }
                        succeed(); return@launch
                    }

                    // Not ready and offline: the network dropping mid-download does not fail
                    // ML Kit, it makes it wait. Say so on the dialog, give up on the offline clock
                    // rather than the 5-minute cap, and don't read anything else into the status
                    // while the downloader is stalled.
                    if (!Connectivity.isOnline(activity)) {
                        if (offlineSinceMs < 0) offlineSinceMs = now
                        if (now - offlineSinceMs >= OFFLINE_GIVE_UP_MS) {
                            Slog.d(TAG) { "offline for ${(now - offlineSinceMs) / 1000} s during the download — giving up" }
                            fail(); return@launch
                        }
                        progress.setMessage(activity.getString(R.string.recognize_downloading_offline_body, elapsedS))
                        continue
                    }
                    offlineSinceMs = -1L

                    when (status) {
                        RecognizerStatus.DOWNLOADING -> {
                            progress.setMessage(activity.getString(R.string.recognize_downloading_body, elapsedS))
                            if (elapsedS >= DOWNLOAD_CAP_S) { fail(); return@launch }
                        }
                        else -> {
                            // NEEDS_DOWNLOAD after prepare() means the chain failed; UNAVAILABLE means
                            // the engine is gone. Either way there is nothing left to wait for.
                            Slog.d(TAG) { "download failed (status $status) after $elapsedS s" }
                            fail(); return@launch
                        }
                    }
                }
                Slog.d(TAG) { "download dialog cancelled — the download continues in the extension" }
            } finally {
                if (progress.isShowing) progress.dismiss()
                if (!settled) onGaveUp()   // cancelled, or the activity went away
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
}
