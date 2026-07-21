package com.notesprout.android.crypto

import android.app.Activity
import androidx.appcompat.app.AlertDialog
import com.notesprout.android.R
import com.notesprout.android.data.soilFile
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * Last-resort unlock path for a notebook the normal open could not key.
 *
 * The rule this enforces: **holding a valid passphrase must always be enough.** Before this, an
 * encrypted `.soil` that failed to open sent the user back to the library with no way to even try a
 * key — a dead end for a file that was perfectly intact. That happens whenever the app's stored key
 * stops matching the file:
 *
 *  - the file was restored from a backup taken before a global conversion or rotation,
 *  - it was re-encrypted on another device under a different global passphrase,
 *  - the index says GLOBAL but the file still carries an older notebook-scope passphrase.
 *
 * [SelfHealingKeyFactory] already covers the case where the passphrase is right and only the cached
 * raw key is stale. This covers the harder case: the passphrase the app knows is genuinely not the
 * one the file was encrypted with, and only the user can supply it.
 *
 * Attempts are rate-limited through [AttemptLimiter] exactly like a normal unlock, so this is not a
 * softer target than the front door. Passphrases are never logged.
 */
object NotebookRecovery {

    enum class Outcome {
        /** A key was recovered (and the file repaired if needed) — the caller should reopen. */
        RETRY,

        /** User declined, cancelled, or no key was found — the caller should fall back. */
        DECLINED,
    }

    /**
     * Offer to unlock [notebookId] with a user-supplied passphrase. Must run on the main thread.
     *
     * Tries the cached global passphrase first (free, and covers a mis-scoped notebook), then
     * prompts. On success the stale derived key is dropped, and a GLOBAL-scope notebook whose file
     * uses a different passphrase is re-keyed to the global one so future opens are ordinary.
     */
    suspend fun offer(
        activity: Activity,
        notebookId: String,
        notebookName: String,
        info: EncryptionInfo,
    ): Outcome {
        if (!info.encrypted) return Outcome.DECLINED
        val file = soilFile(activity, notebookId)
        if (!file.exists() || file.length() == 0L) return Outcome.DECLINED

        val start = confirm(
            activity,
            title = "Can't open $notebookName",
            message = "The saved key didn't unlock this notebook. This usually means the file was " +
                "restored from a backup or encrypted with a different passphrase.\n\n" +
                "If you know a passphrase for it, you can try it now. The notebook itself is intact.",
            positive = "Try a Passphrase",
            negative = "Back to Library",
        )
        if (!start) return Outcome.DECLINED

        // Candidate 0: the cached global. Covers a notebook whose index scope drifted from the file.
        val cachedGlobal = withContext(Dispatchers.IO) { PassphraseStore.getGlobalPassphrase(activity) }
        var recovered: String? = cachedGlobal?.takeIf {
            withContext(Dispatchers.IO) { SoilCrypto.verifyPassphrase(file, it) }
        }

        // Otherwise ask, verifying each attempt against the file itself.
        if (recovered == null) {
            recovered = promptLoop(activity, notebookId, notebookName, file, info)
        }
        val key = recovered ?: return Outcome.DECLINED

        // The cached raw key was derived against a key/salt that no longer applies — drop it so the
        // next open re-derives from this passphrase.
        withContext(Dispatchers.IO) { KeyMaterial.invalidate(activity, notebookId) }

        return when {
            // GLOBAL-scope file on a non-global passphrase: the ordinary open path only ever tries
            // the global one, so leaving it as-is would strand the notebook again on next launch.
            info.keyScope == KeyScope.GLOBAL && cachedGlobal != null && key != cachedGlobal -> {
                val repair = confirm(
                    activity,
                    title = "Repair this notebook?",
                    message = "That passphrase worked, but it isn't your global passphrase. " +
                        "Re-key this notebook to your global passphrase so it opens normally from now on.\n\n" +
                        "The notebook's contents are not changed.",
                    positive = "Repair and Open",
                    negative = "Back to Library",
                )
                if (!repair) return Outcome.DECLINED
                val ok = runCatching {
                    SoilMigrator.rekeyInPlace(file, key, cachedGlobal)
                }.isSuccess
                if (!ok) {
                    notify(activity, "Repair failed", "The notebook could not be re-keyed. It is unchanged.")
                    return Outcome.DECLINED
                }
                withContext(Dispatchers.IO) { KeyMaterial.invalidate(activity, notebookId) }
                Outcome.RETRY
            }

            // NOTEBOOK-scope: the reopen will prompt for exactly this passphrase — hand it over once
            // so the user isn't asked to type the same thing twice.
            info.keyScope == KeyScope.NOTEBOOK -> {
                PassphraseCache.storeOnce(notebookId, key)
                Outcome.RETRY
            }

            else -> Outcome.RETRY
        }
    }

    /** Prompt until the passphrase verifies, the user cancels, or the limiter locks them out. */
    private suspend fun promptLoop(
        activity: Activity,
        notebookId: String,
        notebookName: String,
        file: java.io.File,
        info: EncryptionInfo,
    ): String? {
        val limiterKey = if (info.keyScope == KeyScope.GLOBAL) AttemptLimiter.GLOBAL_KEY else notebookId
        var message = "Enter a passphrase to unlock \"$notebookName\"."
        while (true) {
            val lockedUntil = withContext(Dispatchers.IO) { AttemptLimiter.check(activity, limiterKey) }
            val entered = PassphrasePrompt.promptForPassphrase(
                activity,
                title = "Recover Notebook",
                message = message,
                lockedUntilMs = lockedUntil,
            ) ?: return null

            val valid = withContext(Dispatchers.IO) { SoilCrypto.verifyPassphrase(file, entered) }
            if (valid) {
                withContext(Dispatchers.IO) { AttemptLimiter.recordSuccess(activity, limiterKey) }
                return entered
            }
            withContext(Dispatchers.IO) { AttemptLimiter.recordFailure(activity, limiterKey) }
            message = "That passphrase didn't unlock this notebook. Try another."
        }
    }

    // ── E-ink dialog helpers ─────────────────────────────────────────────────

    private suspend fun confirm(
        activity: Activity,
        title: String,
        message: String,
        positive: String,
        negative: String,
    ): Boolean = suspendCancellableCoroutine { cont ->
        val d = AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(positive) { _, _ -> if (cont.isActive) cont.resume(true) }
            .setNegativeButton(negative) { _, _ -> if (cont.isActive) cont.resume(false) }
            .setOnCancelListener { if (cont.isActive) cont.resume(false) }
            .create()
        d.show()
        d.window?.setElevation(0f)
        d.window?.setBackgroundDrawableResource(R.drawable.shape_dialog_bordered)
        cont.invokeOnCancellation { d.dismiss() }
    }

    private suspend fun notify(activity: Activity, title: String, message: String) =
        suspendCancellableCoroutine { cont ->
            val d = AlertDialog.Builder(activity)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("OK") { _, _ -> if (cont.isActive) cont.resume(Unit) }
                .setOnCancelListener { if (cont.isActive) cont.resume(Unit) }
                .create()
            d.show()
            d.window?.setElevation(0f)
            d.window?.setBackgroundDrawableResource(R.drawable.shape_dialog_bordered)
            cont.invokeOnCancellation { d.dismiss() }
        }
}
