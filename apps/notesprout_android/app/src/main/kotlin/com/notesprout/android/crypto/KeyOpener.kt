package com.notesprout.android.crypto

import android.content.Context
import androidx.sqlite.db.SupportSQLiteOpenHelper
import com.notesprout.android.core.Slog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * Chooses the fastest correct SQLCipher open path for a notebook, backed by the raw-key cache.
 *
 * Opening with the passphrase re-runs SQLCipher's KDF (~300–700 ms) on every connection. The KDF
 * output is the same key every time, so once the derived 32-byte key is cached ([KeyMaterial]) we
 * reopen with it directly (~35 ms). This helper returns a raw-key factory when the key is already
 * cached (RAM/Keystore for GLOBAL, RAM for NOTEBOOK), and otherwise opens with the passphrase for
 * this one connection while deriving the raw key in the background so the next open is fast.
 *
 * The passphrase itself is still needed by callers (KeySession + raw page/export ops), so this only
 * changes how Room opens the file — never how the key is resolved. Key material is never logged.
 */
object KeyOpener {

    private const val TAG = "KeyOpener"
    private val warmScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * OpenHelper.Factory for a notebook [file] whose [fileId] is the notebook id, encrypted under
     * [passphrase] with [scope]. Raw-key on cache hit; passphrase + background warm on miss.
     */
    fun roomFactoryFor(
        context: Context,
        fileId: String,
        file: File,
        scope: KeyScope?,
        passphrase: String,
    ): SupportSQLiteOpenHelper.Factory {
        val cached = when (scope) {
            KeyScope.GLOBAL -> KeyMaterial.peekOrLoad(context, fileId)
            else -> KeyMaterial.peek(fileId)   // NOTEBOOK: RAM only, never persisted
        }
        if (cached != null) {
            Slog.d(TAG) { "raw-key open: $fileId" }
            // The cached key is derived against a specific file salt. If the file behind this id was
            // swapped (restore from backup, re-encrypted elsewhere, re-imported), the key is stale and
            // cannot open it — but the passphrase still can. Never let a stale cache lock the user out.
            return SelfHealingKeyFactory(
                context = context.applicationContext,
                fileId = fileId,
                rawKeyFactory = SoilCrypto.roomFactoryRawKey(cached),
                passphraseFactory = { SoilCrypto.roomFactory(passphrase) },
                // Re-derive against the file's real salt so the next open is a fast raw-key open again.
                onHealed = { warm(context, fileId, file, scope, passphrase) },
            )
        }

        // Cache miss: open with the passphrase now (native KDF, this one connection), and derive the
        // raw key off the critical path so the next open is a fast raw-key open.
        warm(context, fileId, file, scope, passphrase)
        Slog.d(TAG) { "passphrase open (cold; warming raw key): $fileId" }
        return SoilCrypto.roomFactory(passphrase)
    }

    /**
     * Derive + cache the raw key for [file] in the background. Safe to call fire-and-forget from any
     * write path (create / convert / import) so the first open is already a raw-key open. No-op if
     * already cached. Never throws to the caller. GLOBAL persists to the Keystore; NOTEBOOK stays in RAM.
     */
    fun warm(context: Context, fileId: String, file: File, scope: KeyScope?, passphrase: String) {
        val app = context.applicationContext
        warmScope.launch {
            runCatching {
                when (scope) {
                    KeyScope.GLOBAL -> KeyMaterial.rawKeyGlobal(app, fileId, file, passphrase)
                    KeyScope.NOTEBOOK -> KeyMaterial.rawKeyEphemeral(fileId, file, passphrase)
                    null -> {}
                }
            }.onFailure { Slog.d(TAG) { "warm failed for $fileId: ${it.message}" } }
        }
    }
}
