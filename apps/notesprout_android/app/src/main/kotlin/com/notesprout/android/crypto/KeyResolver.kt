package com.notesprout.android.crypto

import android.app.Activity
import com.notesprout.android.data.soilFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Single decision point for obtaining a notebook's SQLCipher key.
 *
 * All open/create/convert/decrypt flows route through here — never construct a key or prompt
 * for a passphrase outside this object. Passphrases are NEVER logged, stored in Intents, or
 * written to the global index.
 */
object KeyResolver {

    /**
     * Resolve the key needed to open [notebookId].
     * Returns null for plaintext notebooks (check [EncryptionInfo.encrypted] to distinguish
     * "plaintext" from "user cancelled" — if [info.encrypted] is true and result is null,
     * the caller must abort the open).
     */
    suspend fun resolveForOpen(activity: Activity, notebookId: String, info: EncryptionInfo): String? {
        if (!info.encrypted) return null
        return when (info.keyScope) {
            KeyScope.GLOBAL -> resolveGlobalForOpen(activity, notebookId)
            KeyScope.NOTEBOOK -> resolveNotebookForOpen(activity, notebookId)
            null -> null
        }
    }

    /**
     * Obtain the key used to encrypt a notebook (create or convert flows).
     * GLOBAL: returns the cached passphrase if available; otherwise prompts with confirm and caches it.
     * NOTEBOOK: always prompts with confirm; never caches.
     * Returns null if the user cancelled.
     */
    suspend fun resolveForConvertToEncrypted(activity: Activity, scope: KeyScope): String? {
        return when (scope) {
            KeyScope.GLOBAL -> {
                val cached = withContext(Dispatchers.IO) { PassphraseStore.getGlobalPassphrase(activity) }
                if (cached != null) {
                    cached
                } else {
                    val passphrase = PassphrasePrompt.promptForPassphrase(
                        activity,
                        title = "Set Global Passphrase",
                        message = "This passphrase will be cached on this device and used for all global-encrypted notebooks.",
                        confirm = true,
                    ) ?: return null
                    withContext(Dispatchers.IO) { PassphraseStore.setGlobalPassphrase(activity, passphrase) }
                    passphrase
                }
            }
            KeyScope.NOTEBOOK -> {
                PassphrasePrompt.promptForPassphrase(
                    activity,
                    title = "Set Notebook Passphrase",
                    message = "You will be prompted for this passphrase every time you open this notebook.",
                    confirm = true,
                )
            }
        }
    }

    /**
     * Resolve the key for a decrypt operation. Always prompts — even if the global passphrase
     * is cached — as an extra confirmation before an irreversible action.
     * Returns null if the user cancelled or the passphrase was wrong.
     */
    suspend fun resolveForDecrypt(activity: Activity, notebookId: String, info: EncryptionInfo): String? {
        if (!info.encrypted) return null
        return promptAndVerify(
            activity, notebookId,
            title = "Enter Passphrase",
            message = "Enter the current passphrase to decrypt this notebook.",
        )
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private suspend fun resolveGlobalForOpen(activity: Activity, notebookId: String): String? {
        val cached = withContext(Dispatchers.IO) { PassphraseStore.getGlobalPassphrase(activity) }
        if (cached != null) {
            val file = soilFile(activity, notebookId)
            val valid = withContext(Dispatchers.IO) { SoilCrypto.verifyPassphrase(file, cached) }
            if (valid) return cached
            // Cached global doesn't match (e.g. notebook authored on another device with a different global) — fall through to prompt.
        }
        return promptAndVerify(
            activity, notebookId,
            title = "Global Passphrase",
            message = "Enter the global passphrase to open this notebook.",
        ) { passphrase ->
            withContext(Dispatchers.IO) { PassphraseStore.setGlobalPassphrase(activity, passphrase) }
        }
    }

    private suspend fun resolveNotebookForOpen(activity: Activity, notebookId: String): String? {
        // Consume the single-use cache set immediately after notebook creation so the user
        // isn't prompted a third time (they just typed it twice to set it).
        PassphraseCache.consumeOnce(notebookId)?.let { return it }
        return promptAndVerify(
            activity, notebookId,
            title = "Notebook Passphrase",
            message = "Enter the passphrase for this notebook.",
        )
    }

    /**
     * Prompt for a passphrase, verify it against [notebookId]'s file, and loop on wrong passphrase.
     * [onSuccess] runs (before returning) when the passphrase is verified.
     * Returns null if the user cancels at any point.
     */
    private suspend fun promptAndVerify(
        activity: Activity,
        notebookId: String,
        title: String,
        message: String,
        onSuccess: suspend (String) -> Unit = {},
    ): String? {
        var promptMessage = message
        while (true) {
            val passphrase = PassphrasePrompt.promptForPassphrase(activity, title, promptMessage) ?: return null
            val file = soilFile(activity, notebookId)
            val valid = withContext(Dispatchers.IO) { SoilCrypto.verifyPassphrase(file, passphrase) }
            if (valid) {
                onSuccess(passphrase)
                return passphrase
            }
            promptMessage = "Wrong passphrase. Try again."
        }
    }
}
