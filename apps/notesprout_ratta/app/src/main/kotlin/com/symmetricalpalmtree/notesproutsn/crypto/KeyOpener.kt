package com.symmetricalpalmtree.notesproutsn.crypto

import android.content.Context
import androidx.sqlite.db.SupportSQLiteOpenHelper
import com.symmetricalpalmtree.notesproutsn.core.Slog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * Chooses the fastest correct open path for an **existing** encrypted file, backed by the raw-key
 * cache ([KeyMaterial]).
 *
 * Cache hit: the raw key is verified against the file first (a cheap open, ~35 ms). A stale key
 * (the file behind this id was swapped, so its salt changed) is invalidated instead of locking the
 * user out, and the passphrase path is taken. Cache miss: passphrase open now (native KDF on this
 * one connection) while the raw key is derived in the background so the next open is fast.
 *
 * Blocking (verify, possibly KDF): call on Dispatchers.IO. Key material is never logged.
 */
object KeyOpener {

    private const val TAG = "KeyOpener"
    /** One derive at a time (arc 26 / U3): a run that opens many cold files in a row must queue
     *  its warms, not race them — each is a full KDF, and a burst of concurrent ones is what
     *  exhausted native memory on the Nomad. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val warmScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    /** Room factory for the existing encrypted [file] identified by [fileId]. Throws
     *  [SoilLockedException] if the file is missing/empty — this path never creates. */
    fun roomFactoryFor(context: Context, fileId: String, file: File, passphrase: String): SupportSQLiteOpenHelper.Factory {
        SoilCrypto.requireExisting(file)
        val cached = KeyMaterial.peekOrLoad(context, fileId)
        if (cached != null) {
            if (SoilCrypto.verifyRawKey(file, cached)) {
                Slog.d(TAG) { "raw-key open: $fileId" }
                return SoilCrypto.roomFactoryRawKey(cached)
            }
            Slog.d(TAG) { "cached raw key stale for $fileId — invalidating" }
            KeyMaterial.invalidate(context, fileId)
        }
        warm(context, fileId, file, passphrase)
        Slog.d(TAG) { "passphrase open (cold; warming raw key): $fileId" }
        return SoilCrypto.roomFactory(passphrase)
    }

    /**
     * [roomFactoryFor] over a [KeyResolver] answer (arc 26 / U4). `Passphrases` tries the cached raw key
     * first as always, then its candidates: one candidate is used as-is (today's cold path, no
     * verify); two — a rotation in flight — are each verified (one KDF apiece) and the first that
     * fits is used. `Unlocked` verifies the raw key and uses it. `NeedsPrompt` / `NoKey` throw
     * [SoilLockedException]: this path never prompts, and a caller that can must resolve first.
     */
    fun roomFactoryFor(context: Context, fileId: String, file: File, resolved: KeyResolver.Resolved): SupportSQLiteOpenHelper.Factory {
        SoilCrypto.requireExisting(file)
        when (resolved) {
            is KeyResolver.Resolved.Passphrases -> {
                val candidates = resolved.candidates
                if (candidates.size == 1) return roomFactoryFor(context, fileId, file, candidates[0])
                val cached = KeyMaterial.peekOrLoad(context, fileId)
                if (cached != null) {
                    if (SoilCrypto.verifyRawKey(file, cached)) return SoilCrypto.roomFactoryRawKey(cached)
                    KeyMaterial.invalidate(context, fileId)
                }
                val fitting = candidates.firstOrNull { SoilCrypto.verifyPassphrase(file, it) }
                    ?: throw SoilLockedException("no candidate key opens $fileId")
                warm(context, fileId, file, fitting)
                Slog.d(TAG) { "passphrase open (cold; candidate ${candidates.indexOf(fitting)}): $fileId" }
                return SoilCrypto.roomFactory(fitting)
            }
            is KeyResolver.Resolved.Unlocked -> {
                if (SoilCrypto.verifyRawKey(file, resolved.rawKey)) {
                    Slog.d(TAG) { "raw-key open (unlocked): $fileId" }
                    return SoilCrypto.roomFactoryRawKey(resolved.rawKey)
                }
                Slog.d(TAG) { "unlocked raw key stale for $fileId — invalidating" }
                KeyMaterial.invalidate(context, fileId)
                NotebookUnlocks.forget(fileId)
                throw SoilLockedException("stale raw key for $fileId")
            }
            KeyResolver.Resolved.NeedsPrompt -> throw SoilLockedException("$fileId needs its passphrase")
            KeyResolver.Resolved.NoKey -> throw SoilLockedException("no global key")
        }
    }

    /** Derive + cache [file]'s raw key in the background. No-op if cached. Never throws. */
    fun warm(context: Context, fileId: String, file: File, passphrase: String) {
        val app = context.applicationContext
        warmScope.launch {
            val t0 = android.os.SystemClock.elapsedRealtime()
            runCatching { KeyMaterial.rawKey(app, fileId, file, passphrase) }
                .onSuccess { Slog.d(TAG) { "warmed $fileId in ${android.os.SystemClock.elapsedRealtime() - t0} ms" } }
                .onFailure { Slog.d(TAG) { "warm failed for $fileId: ${it.message}" } }
        }
    }
}
