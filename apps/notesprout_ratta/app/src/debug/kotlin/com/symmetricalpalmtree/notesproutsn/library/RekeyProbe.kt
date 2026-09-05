package com.symmetricalpalmtree.notesproutsn.library

import android.content.Context
import android.os.SystemClock
import com.symmetricalpalmtree.notesproutsn.crypto.KeyMaterial
import com.symmetricalpalmtree.notesproutsn.crypto.KeyOpener
import com.symmetricalpalmtree.notesproutsn.crypto.KeySession
import com.symmetricalpalmtree.notesproutsn.crypto.RekeyNames
import com.symmetricalpalmtree.notesproutsn.crypto.SoilCrypto
import com.symmetricalpalmtree.notesproutsn.crypto.SoilRekey
import com.symmetricalpalmtree.notesproutsn.data.soil.KEY_SCOPE_GLOBAL
import com.symmetricalpalmtree.notesproutsn.data.soil.SoilOpenFiles
import com.symmetricalpalmtree.notesproutsn.data.soil.SoilSchema
import com.symmetricalpalmtree.notesproutsn.data.soilFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Debug build only — the on-device proof of arc 26 / U2's [SoilRekey], since SQLCipher is not on
 * the JVM. Two tools over one alive notebook picked from the library's index:
 *
 *  - [roundTrip]: `integrity_check` + row count under the global key → rekey to a throwaway
 *    passphrase → the throwaway opens it and the global no longer does → rekey back → the same
 *    checks under the global, the raw-key cache invalidated and re-warmed. Timings in the report.
 *  - [breakCommit]: reproduce a death **between the two renames** of `commitReplace` by hand,
 *    which no `am force-stop` can time: the original steps aside as `X.old.bak`, an `X.rekey.tmp`
 *    is left beside it — either a byte copy that verifies under the global (variant A → Bootstrap
 *    renames the tmp in) or garbage (variant B → Bootstrap renames the `.old.bak` back). The caller
 *    kills the process afterwards; the next launch runs `recoverGarden`, and the notebook opens.
 *
 * Runs on the `.dev` package over the dev library's real notebooks — a throwaway notebook created
 * for the walk is the convention. No passphrase is logged or reported.
 */
object RekeyProbe {

    private const val THROWAWAY = "probe-throwaway-passphrase"

    suspend fun roundTrip(context: Context, notebookId: String): String = withContext(Dispatchers.IO) {
        val global = KeySession.get() ?: return@withContext "FAIL — no key in session"
        val file = soilFile(context, notebookId)
        if (SoilOpenFiles.isOpen(file)) return@withContext "FAIL — notebook is open in this process"
        val out = StringBuilder()
        try {
            val before = inspect(file, global)
            out.append("before: ${before.first} · ${before.second} rows · ${file.length()} B\n")

            var t = SystemClock.elapsedRealtime()
            SoilRekey.rekeyInPlace(context, file, notebookId, global, THROWAWAY, KEY_SCOPE_GLOBAL)
            out.append("→ throwaway: ${SystemClock.elapsedRealtime() - t} ms\n")
            out.append("  throwaway opens: ${SoilCrypto.verifyPassphrase(file, THROWAWAY)}\n")
            out.append("  global opens:    ${SoilCrypto.verifyPassphrase(file, global)}  (want false)\n")
            out.append("  raw key cached:  ${KeyMaterial.peekOrLoad(context, notebookId) != null}  (want false)\n")
            val mid = inspect(file, THROWAWAY)
            out.append("  mid: ${mid.first} · ${mid.second} rows\n")

            t = SystemClock.elapsedRealtime()
            SoilRekey.rekeyInPlace(context, file, notebookId, THROWAWAY, global, KEY_SCOPE_GLOBAL)
            out.append("→ global: ${SystemClock.elapsedRealtime() - t} ms\n")
            out.append("  global opens:    ${SoilCrypto.verifyPassphrase(file, global)}\n")
            out.append("  throwaway opens: ${SoilCrypto.verifyPassphrase(file, THROWAWAY)}  (want false)\n")
            val after = inspect(file, global)
            out.append("after: ${after.first} · ${after.second} rows · ${file.length()} B\n")
            out.append("leftovers: ${SoilRekey.hasLeftovers(file)}  (want false)\n")
            val ok = before.first == "ok" && mid.first == "ok" && after.first == "ok" &&
                before.second == after.second && before.second == mid.second && !SoilRekey.hasLeftovers(file)
            out.append(if (ok) "PASS" else "FAIL")
        } catch (e: Exception) {
            out.append("FAIL — ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            KeyOpener.warm(context, notebookId, file, global)
        }
        out.toString()
    }

    /** `integrity_check` verdict + `notebook` row count under [passphrase]. */
    private fun inspect(file: File, passphrase: String): Pair<String, Long> {
        val db = SoilCrypto.openRaw(file, passphrase)
        try {
            val integrity = db.rawQuery("PRAGMA integrity_check", null).use { c ->
                if (c.moveToFirst()) c.getString(0) else "?"
            }
            val rows = db.rawQuery("SELECT count(*) FROM ${SoilSchema.TABLE}", null).use { c ->
                if (c.moveToFirst()) c.getLong(0) else -1L
            }
            return integrity to rows
        } finally {
            runCatching { db.close() }
        }
    }

    enum class Break { TMP_VERIFIES, TMP_GARBAGE }

    /** Leave the Garden as a commit that died between its two renames would. The caller kills the
     *  process. Refuses a notebook that is open or has a non-empty WAL (the copy would be partial). */
    suspend fun breakCommit(context: Context, notebookId: String, variant: Break): String = withContext(Dispatchers.IO) {
        val file = soilFile(context, notebookId)
        if (SoilOpenFiles.isOpen(file)) return@withContext "FAIL — notebook is open in this process"
        val wal = File(file.path + "-wal")
        if (wal.exists() && wal.length() > 0L) return@withContext "FAIL — notebook has a non-empty WAL; open and close it first"
        val tmp = RekeyNames.tmpFor(file)
        val bak = RekeyNames.bakFor(file)
        try {
            when (variant) {
                Break.TMP_VERIFIES -> file.copyTo(tmp, overwrite = true)
                Break.TMP_GARBAGE -> tmp.writeBytes(ByteArray(4096) { (it * 31).toByte() })
            }
            if (!file.renameTo(bak)) return@withContext "FAIL — could not rename the original aside"
            "Garden now: ${bak.name.substringAfterLast('.')} + ${tmp.name.substringAfterLast('.')}, original gone. " +
                "Kill → relaunch → Bootstrap should ${if (variant == Break.TMP_VERIFIES) "rename the tmp in" else "rename the .old.bak back"}."
        } catch (e: Exception) {
            "FAIL — ${e.javaClass.simpleName}: ${e.message}"
        }
    }
}
