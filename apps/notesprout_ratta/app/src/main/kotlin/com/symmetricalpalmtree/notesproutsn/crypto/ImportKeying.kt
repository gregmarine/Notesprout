package com.symmetricalpalmtree.notesproutsn.crypto

import android.util.Log
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.data.soil.KEY_SCOPE_GLOBAL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * **The import keying transform** (arc 16 / I1) — [ExportKeying]'s mirror, and the reason the
 * arc-16 wizard could drop og's import keying chooser entirely: SN only ever opens files under
 * this device's global key, so **every accepted import is re-keyed to it**, unconditionally.
 * Plaintext is encrypted; a foreign passphrase (another device's GLOBAL export, or a NOTEBOOK
 * re-keyed export) is re-keyed; a file the global passphrase already opens (a same-device Keep
 * export) passes through byte-untouched — a pure copy needs no transform, **but it earns no free
 * acceptance either**: the pass-through still opens the file and requires `integrity_check` = ok
 * (the I2 review's finding — the most common path was the only one accepting an unverified file,
 * and a corrupt same-device export answering an id-collision *Replace* would have overwritten a
 * healthy notebook).
 *
 * The mechanism **is** [ExportKeying]'s — literally: both transform cases run
 * [ExportKeying.exportAndKeyToPrimary], the shared destination-primary export-and-key core
 * (export-and-key, never `PRAGMA rekey` — og's recorded on-device finding), with the og traps
 * pinned in one place: `user_version` carried by hand and re-verified from the finished file, the
 * meta restamped (here `encrypted: true`, `keyScope: GLOBAL`), and nothing accepted without
 * probing encrypted + opening under the destination key + `integrity_check` = ok.
 *
 * Everything runs **only in the import cache** (`cacheDir/import/`): the transform writes a
 * sibling of the incoming file and a failure deletes only that unaccepted sibling — never the
 * incoming copy, and the Garden is not in sight yet (never-delete-on-corruption covers the temps
 * too). Passphrases appear here only as SQL string literals on a local connection
 * ([ExportKeying.sqlLiteral] — pure, pinned by test) and are never logged, never in a name,
 * never in a message.
 */
object ImportKeying {

    private const val TAG = "ImportKeying"

    /** How the incoming file opens — decided by the pipeline's probe + verify (and, for a foreign
     *  file, the user's typed passphrase). */
    sealed interface Opening {
        /** Probed plaintext — no key at all. */
        data object Plaintext : Opening

        /** Probed encrypted and [passphrase] verified against it. */
        data class Encrypted(val passphrase: String) : Opening
    }

    /**
     * Return a file in the import cache that opens under [globalPassphrase]: [incoming] itself
     * when it already does (integrity-verified in place), an accepted re-keyed sibling otherwise.
     * IO throughout; throws [IllegalStateException] with a path-free message on any failure,
     * deleting only its own unaccepted output — never the incoming copy.
     */
    suspend fun toGlobal(incoming: File, opening: Opening, globalPassphrase: String): File =
        when (opening) {
            is Opening.Encrypted ->
                if (opening.passphrase == globalPassphrase) acceptPassThrough(incoming, globalPassphrase)
                else transform(incoming, ExportKeying.sqlLiteral(opening.passphrase), globalPassphrase)
            Opening.Plaintext ->
                // `''` is how a plaintext ATTACH key is spelled.
                transform(incoming, "''", globalPassphrase)
        }

    /** Both transform cases are one call into the shared core — only the ATTACH key differs. */
    private suspend fun transform(incoming: File, attachKeyLiteral: String, globalPassphrase: String): File =
        ExportKeying.exportAndKeyToPrimary(
            out = sibling(incoming),
            sourcePath = incoming.path,
            attachKeyLiteral = attachKeyLiteral,
            destPassphrase = globalPassphrase,
            keyScope = KEY_SCOPE_GLOBAL,
            what = "imported",
        )

    /**
     * The pass-through's acceptance: the file already opens under the device key, so no bytes
     * change — but untrusted input still earns a whole-file `integrity_check` before anything
     * downstream may read it. Verification only: the incoming file is never deleted here,
     * whatever the answer (never-delete-on-corruption).
     */
    private suspend fun acceptPassThrough(incoming: File, globalPassphrase: String): File =
        withContext(Dispatchers.IO) {
            val db = try {
                SoilCrypto.openRaw(incoming, globalPassphrase)
            } catch (e: Exception) {
                Log.w(TAG, "pass-through open failed: ${e.javaClass.simpleName}")
                throw IllegalStateException("the imported file could not be read back")
            }
            try {
                val integrity = db.rawQuery("PRAGMA integrity_check", null).use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
                if (integrity != "ok") {
                    Log.w(TAG, "pass-through integrity check answered ${integrity?.take(40) ?: "nothing"}")
                    throw IllegalStateException("the imported file failed its integrity check")
                }
            } finally {
                runCatching { db.close() }
            }
            Slog.d(TAG) { "already under the device key — pass-through accepted (${incoming.length()} bytes)" }
            incoming
        }

    /** The output path beside its input, with any leftovers of an earlier aborted run — sidecars
     *  included — cleared first ([ExportKeying.rejectOutput], never pointed at an input). */
    private fun sibling(incoming: File): File {
        val out = File(incoming.parentFile, "${incoming.nameWithoutExtension}-keyed.soil")
        ExportKeying.rejectOutput(out)
        return out
    }
}
