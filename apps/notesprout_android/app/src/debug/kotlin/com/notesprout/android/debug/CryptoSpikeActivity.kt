package com.notesprout.android.debug

import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.notesprout.android.crypto.SoilCrypto
import com.notesprout.android.crypto.SoilMigrator
import com.notesprout.android.data.SoilDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.zetetic.database.sqlcipher.SQLiteDatabase as ZeticDB
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.io.File
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Phase-0 encryption performance spike (global-encryption go/no-go).
 *
 * Measures, on the *real* notebooks pushed into this debug build's Garden, what SQLCipher
 * actually costs so we know whether "encrypt everything" is viable and whether we need the
 * derive-once raw-key session cache. Debug source set only — never ships.
 *
 * Per plaintext notebook it reports:
 *   - plaintext Room open + first query (baseline: what users pay today)
 *   - passphrase (KDF) Room open + first query (what encrypt-everything would cost)
 *   - isolated PBKDF2-HMAC-SHA512(256000) time — the KDF alone, i.e. the savings a raw-key
 *     cache would recover on every open
 *   - verifyPassphrase() time — the *redundant* second KDF open KeyResolver does today
 *   - raw-key open time + a portability check (does PBKDF2(pass,salt) reproduce the file key?)
 *   - encryptInPlace() time — the per-notebook cost of the Phase-4 bulk-conversion sweep
 *
 * Then the same for the global index (notesprout.db) to size the cold-launch hit.
 *
 * Launch: adb shell am start -n com.notesprout.android.dev/com.notesprout.android.debug.CryptoSpikeActivity
 * Results: logcat tag "CryptoSpike" + a file at Android/data/<pkg>/files/crypto_spike_<MODEL>.txt
 */
class CryptoSpikeActivity : AppCompatActivity() {

    private lateinit var out: TextView
    private val sb = StringBuilder()

    // SQLCipher 4.x defaults — the spike verifies these are correct via the raw-key portability check.
    private val TEST_PASS = "spike-benchmark-passphrase-2026"
    private val KDF_ITER = 256_000
    private val KDF_DKLEN = 32   // AES-256 key
    private val KDF_SALT_LEN = 16
    private val MAX_NOTEBOOKS = 25   // cap runtime on very large libraries
    private val REPS = 4            // rep[0] = coldest available; min(rep[1..]) = warm floor
    private val PERF_GATE_MS = 150.0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        out = TextView(this).apply {
            textSize = 10f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(24, 24, 24, 24)
            setTextIsSelectable(true)
        }
        setContentView(ScrollView(this).apply { addView(out, MATCH_PARENT, MATCH_PARENT) })
        lifecycleScope.launch { runSpike() }
    }

    private fun emit(line: String) {
        Log.i("CryptoSpike", line)
        sb.appendLine(line)
        runOnUiThread { out.text = sb.toString() }
    }

    private suspend fun runSpike() = withContext(Dispatchers.IO) {
        emit("=== Crypto Spike — ${Build.MODEL} (${Build.DEVICE}) ===")
        emit("SQLCipher via net.zetetic:sqlcipher-android:4.6.1 · KDF iter=$KDF_ITER")
        emit("gate: encrypted open must add ≤ ${PERF_GATE_MS.toInt()}ms over plaintext")
        emit("")

        val garden = File(getExternalFilesDir(null), "Garden")
        val spikeDir = File(cacheDir, "spike").apply { deleteRecursively(); mkdirs() }

        val plaintextSoils = (garden.listFiles { f -> f.name.endsWith(".soil") } ?: emptyArray())
            .filter { isPlaintextSqlite(it) }
            .sortedBy { it.length() }
        if (plaintextSoils.isEmpty()) {
            emit("No plaintext .soil files in $garden — push the stable library first.")
            return@withContext
        }
        val sample = if (plaintextSoils.size > MAX_NOTEBOOKS) plaintextSoils.take(MAX_NOTEBOOKS) else plaintextSoils
        emit("Found ${plaintextSoils.size} plaintext notebooks; benchmarking ${sample.size}.")
        emit("")
        emit(header())
        emit("-".repeat(header().length))

        val rows = mutableListOf<Row>()
        for ((i, soil) in sample.withIndex()) {
            runOnUiThread { title("notebook ${i + 1}/${sample.size}: ${soil.name.take(12)}") }
            val row = benchmarkNotebook(soil, spikeDir)
            rows.add(row)
            emit(row.format())
        }

        emit("")
        summarize(rows, plaintextSoils.size)

        emit("")
        emit("=== Global index (notesprout.db) ===")
        benchmarkIndex(spikeDir)

        emit("")
        probeDerivationAndRoom(sample.first(), spikeDir)

        emit("")
        validateProductionRawKeyPath(sample.first(), spikeDir)

        spikeDir.deleteRecursively()

        val report = File(getExternalFilesDir(null), "crypto_spike_${Build.MODEL.replace(" ", "_")}.txt")
        runCatching { report.writeText(sb.toString()) }
        emit("")
        emit("Report written: ${report.absolutePath}")
        runOnUiThread { title("done") }
    }

    // ── Per-notebook benchmark ──────────────────────────────────────────────

    private suspend fun benchmarkNotebook(soil: File, spikeDir: File): Row {
        val sizeKB = soil.length() / 1024
        val plain = File(spikeDir, "plain.soil")
        val enc = File(spikeDir, "enc.soil")

        // Plaintext Room open + first query.
        copyClean(soil, plain)
        val plainReps = repsOf { roomOpenQuery(plain, key = null) }
        cleanSoil(plain)

        // Encrypt a fresh copy; time the conversion (Phase-4 sweep unit cost).
        copyClean(soil, enc)
        val encryptMs = measureMs { SoilMigrator.encryptInPlace(enc, TEST_PASS) }

        // Passphrase (KDF) Room open + first query.
        val encReps = repsOf { roomOpenQuery(enc, key = TEST_PASS) }

        // Redundant verify open (KeyResolver does this before the Room open).
        val verifyMs = measureMs { SoilCrypto.verifyPassphrase(enc, TEST_PASS) }

        // Isolated KDF cost (the raw-key cache would recover this per open).
        val salt = readSalt(enc)
        val kdfMs = measureMs { pbkdf2HmacSha512(TEST_PASS.toByteArray(Charsets.UTF_8), salt, KDF_ITER, KDF_DKLEN) }

        // Raw-key open: derive once, open with x'<hex>'. Portability = does the derived key
        // open a file that was encrypted with the *passphrase*? (proves CLI-openability survives)
        val rawKey = pbkdf2HmacSha512(TEST_PASS.toByteArray(Charsets.UTF_8), salt, KDF_ITER, KDF_DKLEN)
        val (rawMs, rawOk) = rawKeyOpenQuery(enc, rawKey)
        cleanSoil(enc)

        return Row(soil.name.take(12), sizeKB, plainReps, encReps, verifyMs, kdfMs, rawMs, rawOk, encryptMs)
    }

    /**
     * Build the real Room path (SoilDatabase.builder + optional keyed factory), force open, one query.
     * Non-fatal: Room's strict schema validation rejects notebooks whose columnar v4 column set drifts
     * from this build's entity (a data artifact, unrelated to crypto cost). Return -1.0 so the row's
     * raw crypto metrics (verify / raw-key / encrypt) still populate on those devices.
     */
    private fun roomOpenQuery(file: File, key: String?): Double = try {
        measureMs {
            val builder = SoilDatabase.builder(this, file.absolutePath)
            if (key != null) builder.openHelperFactory(SoilCrypto.roomFactory(key))
            val db = builder.build()
            try {
                db.openHelper.readableDatabase
                    .query("SELECT count(*) FROM notebook").use { it.moveToFirst() }
            } finally {
                db.close()
            }
        }
    } catch (e: Exception) {
        Log.w("CryptoSpike", "Room open failed (${if (key == null) "plain" else "enc"}): ${e.message}")
        -1.0
    }

    /** Open with a pre-derived raw key via PRAGMA key = "x'..'". Returns (ms, opened-ok). */
    private fun rawKeyOpenQuery(file: File, rawKey: ByteArray): Pair<Double, Boolean> {
        return try {
            var ok = false
            val ms = measureMs {
                val db = ZeticDB.openOrCreateDatabase(file, "x'${rawKey.toHex()}'", null, null)
                try {
                    db.rawQuery("SELECT count(*) FROM notebook", null).use { ok = it.moveToFirst() }
                } finally {
                    db.close()
                }
            }
            ms to ok
        } catch (e: Exception) {
            Log.w("CryptoSpike", "raw-key open failed: ${e.message}")
            -1.0 to false
        }
    }

    // ── Index benchmark ─────────────────────────────────────────────────────

    private suspend fun benchmarkIndex(spikeDir: File) {
        val idx = File(getExternalFilesDir(null), "notesprout.db")
        if (!idx.exists()) { emit("notesprout.db not found — skipped."); return }
        val plain = File(spikeDir, "idx_plain.db")
        val enc = File(spikeDir, "idx_enc.db")

        copyClean(idx, plain)
        val plainMs = repsOf {
            measureMs {
                val db = android.database.sqlite.SQLiteDatabase.openDatabase(
                    plain.absolutePath, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY
                )
                try { db.rawQuery("SELECT count(*) FROM objects", null).use { it.moveToFirst() } }
                finally { db.close() }
            }
        }

        copyClean(idx, enc)
        val encryptMs = measureMs { SoilMigrator.encryptInPlace(enc, TEST_PASS) }
        val encMs = repsOf {
            measureMs {
                val db = ZeticDB.openOrCreateDatabase(enc, TEST_PASS, null, null)
                try { db.rawQuery("SELECT count(*) FROM objects", null).use { it.moveToFirst() } }
                finally { db.close() }
            }
        }
        val rawKey = pbkdf2HmacSha512(TEST_PASS.toByteArray(Charsets.UTF_8), readSalt(enc), KDF_ITER, KDF_DKLEN)
        val (rawMs, rawOk) = try {
            var ok = false
            val ms = measureMs {
                val db = ZeticDB.openOrCreateDatabase(enc, "x'${rawKey.toHex()}'", null, null)
                try { db.rawQuery("SELECT count(*) FROM objects", null).use { ok = it.moveToFirst() } }
                finally { db.close() }
            }
            ms to ok
        } catch (e: Exception) { -1.0 to false }

        emit("index size:        ${idx.length() / 1024} KB")
        emit("plaintext open:    cold=${plainMs.cold.f()}  warm=${plainMs.warm.f()}")
        emit("passphrase open:   cold=${encMs.cold.f()}  warm=${encMs.warm.f()}")
        emit("raw-key open:      ${if (rawMs < 0) "FAILED" else rawMs.f()}  portable=${if (rawMs < 0) "-" else rawOk}")
        emit("one-time encrypt:  ${encryptMs.f()}  (migration cost, once)")
    }

    // ── Summary / go-no-go ──────────────────────────────────────────────────

    private fun summarize(rows: List<Row>, totalNotebooks: Int) {
        if (rows.isEmpty()) return
        fun avg(sel: (Row) -> Double) = rows.map(sel).filter { it >= 0 }.let { if (it.isEmpty()) -1.0 else it.average() }
        val plainWarm = avg { it.plain.warm }
        val encWarm = avg { it.enc.warm }
        val encCold = avg { it.enc.cold }
        val plainCold = avg { it.plain.cold }
        val kdf = avg { it.kdfMs }
        val verify = avg { it.verifyMs }
        val raw = avg { it.rawMs }
        val encrypt = avg { it.encryptMs }
        val allPortable = rows.all { it.rawMs < 0 || it.rawOk }

        emit("=== Averages (ms) ===")
        emit("plaintext open:       cold=${plainCold.f()}  warm=${plainWarm.f()}")
        emit("passphrase open:      cold=${encCold.f()}  warm=${encWarm.f()}")
        emit("  Δ added by KDF:      cold=+${(encCold - plainCold).f()}  warm=+${(encWarm - plainWarm).f()}")
        emit("isolated PBKDF2:      ${kdf.f()}   (per-open savings a raw-key cache recovers)")
        emit("redundant verify:     ${verify.f()}   (2nd KDF today; removable)")
        emit("raw-key open:         ${if (raw < 0) "n/a" else raw.f()}   portable=$allPortable")
        emit("encryptInPlace/nb:    ${encrypt.f()}")
        emit("")
        val projectedSweep = if (encrypt >= 0) (encrypt * totalNotebooks / 1000.0) else -1.0
        emit("Phase-4 sweep estimate: ~${projectedSweep.f1()}s for all $totalNotebooks notebooks (one-time, resumable)")
        emit("")
        val warmDelta = encWarm - plainWarm
        val verdict = when {
            warmDelta <= PERF_GATE_MS -> "PASS — encrypted open adds ${warmDelta.f()}ms (≤ gate). Raw-key cache optional."
            raw in 0.0..(plainWarm + PERF_GATE_MS) -> "PASS with raw-key cache — passphrase open is +${warmDelta.f()}ms (over gate), " +
                "but raw-key open is ${raw.f()}ms. Build the derive-once cache in Phase 1."
            else -> "NEEDS REVIEW — passphrase open +${warmDelta.f()}ms over gate and raw-key open didn't clear it. Decide together."
        }
        emit("VERDICT: $verdict")
    }

    // ── Phase-1a probe: fast byte-exact derivation + Room raw-key open ───────

    /**
     * Settles the two Phase-1a unknowns on-device, for BOTH an ASCII and a UTF-8 passphrase:
     *   1) Can native SecretKeyFactory(PBKDF2WithHmacSHA512) derive the SQLCipher key fast AND
     *      byte-exactly (does the derived key open the passphrase-encrypted file)? Compared to the
     *      hand-rolled Mac loop (known correct but slow).
     *   2) Does a Room connection open in raw-key mode via SupportOpenHelperFactory("x'<hex>'".bytes)
     *      — i.e. ~3ms (KDF skipped) vs ~300ms (fell back to KDF)?
     */
    private suspend fun probeDerivationAndRoom(soil: File, spikeDir: File) {
        emit("=== Phase-1a probe: derivation speed + Room raw-key ===")
        val enc = File(spikeDir, "probe.soil")
        val cases = listOf("ascii" to "spike-benchmark-passphrase-2026", "utf8" to "café-日本語-🔐-pw")
        for ((label, pass) in cases) {
            copyClean(soil, enc)
            SoilMigrator.encryptInPlace(enc, pass)
            val salt = readSalt(enc)

            var skfMs = -1.0; var skfKey: ByteArray? = null
            try { skfMs = measureMs { skfKey = deriveSkf(pass, salt) } } catch (e: Exception) {
                emit("$label: SecretKeyFactory unavailable/failed: ${e.message}")
            }
            var macKey: ByteArray? = null
            val macMs = measureMs { macKey = pbkdf2HmacSha512(pass.toByteArray(Charsets.UTF_8), salt, KDF_ITER, KDF_DKLEN) }

            val skfOpens = skfKey?.let { rawKeyOpens(enc, it) } ?: false
            val macOpens = macKey?.let { rawKeyOpens(enc, it) } ?: false
            val equal = (skfKey != null && macKey != null && skfKey!!.contentEquals(macKey!!))

            emit("$label: SKF derive=${skfMs.f()}ms opens=$skfOpens | Mac derive=${macMs.f()}ms opens=$macOpens | keysEqual=$equal")

            // Room raw-key open, using a key we trust opens (prefer Mac; it's UTF-8-exact by construction).
            val roomKey = macKey?.takeIf { macOpens } ?: skfKey
            if (roomKey != null) {
                val roomMs = roomRawKeyOpen(enc, roomKey)
                emit("$label: Room raw-key open=${if (roomMs < 0) "FAIL/crash" else roomMs.f() + "ms"} " +
                    "(≈3ms=raw skipped KDF, ≈300ms=fell back to KDF)")
            }
            cleanSoil(enc)
        }
    }

    /** Native PBKDF2-HMAC-SHA512 via SecretKeyFactory. char[] password → provider-defined byte encoding. */
    private fun deriveSkf(pass: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pass.toCharArray(), salt, KDF_ITER, KDF_DKLEN * 8)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512").generateSecret(spec).encoded
    }

    /** True if a raw zetetic open with x'<hex>' succeeds (proves the derived key matches the file). */
    private fun rawKeyOpens(file: File, rawKey: ByteArray): Boolean = try {
        val db = ZeticDB.openOrCreateDatabase(file, "x'${rawKey.toHex()}'", null, null)
        db.rawQuery("SELECT count(*) FROM sqlite_master", null).use { it.moveToFirst() }
        db.close(); true
    } catch (e: Exception) { false }

    /** Room open with a raw key via SupportOpenHelperFactory(ASCII bytes of x'<hex>'). */
    private fun roomRawKeyOpen(file: File, rawKey: ByteArray): Double = try {
        measureMs {
            val factory = SupportOpenHelperFactory("x'${rawKey.toHex()}'".toByteArray(Charsets.US_ASCII))
            val db = SoilDatabase.builder(this, file.absolutePath).openHelperFactory(factory).build()
            try { db.openHelper.readableDatabase.query("SELECT count(*) FROM notebook").use { it.moveToFirst() } }
            finally { db.close() }
        }
    } catch (e: Exception) { Log.w("CryptoSpike", "Room raw-key open: ${e.message}"); -1.0 }

    // ── Phase-1a validation: exercise the real production classes on-device ──

    /**
     * Drives the actual Phase-1a production path (RawKeyDerivation / KeyMaterial / DerivedKeyStore /
     * SoilCrypto raw-key helpers) end-to-end against a real encrypted notebook — proving correctness,
     * Keystore persistence, and the cache-hit speedup with the code we'll ship (not the spike's ad-hoc calls).
     */
    private suspend fun validateProductionRawKeyPath(soil: File, spikeDir: File) {
        emit("=== Phase-1a validation: production raw-key path ===")
        val enc = File(spikeDir, "prod.soil")
        val pass = "spike-benchmark-passphrase-2026"
        val fileId = "spike-validation-id"
        copyClean(soil, enc)
        SoilMigrator.encryptInPlace(enc, pass)
        try {
            val deriveMs = measureMs { com.notesprout.android.crypto.RawKeyDerivation.deriveKey(enc, pass) }
            val key = com.notesprout.android.crypto.RawKeyDerivation.deriveKey(enc, pass)

            // KeyMaterial: first call derives+persists; second hits RAM.
            com.notesprout.android.crypto.KeyMaterial.invalidate(this, fileId)
            val km1Ms = measureMs { com.notesprout.android.crypto.KeyMaterial.rawKeyGlobal(this, fileId, enc, pass) }
            val km2Ms = measureMs { com.notesprout.android.crypto.KeyMaterial.rawKeyGlobal(this, fileId, enc, pass) }
            val persisted = com.notesprout.android.crypto.DerivedKeyStore.get(this, fileId)
            val persistedOk = persisted != null && persisted.contentEquals(key)

            val verifyOk = SoilCrypto.verifyRawKey(enc, key)
            var rawCount = -1
            val rawMs = measureMs {
                val db = SoilCrypto.openRawEncryptedRawKey(enc, key)
                try { db.rawQuery("SELECT count(*) FROM notebook", null).use { if (it.moveToFirst()) rawCount = it.getInt(0) } }
                finally { db.close() }
            }
            val roomMs = try {
                measureMs {
                    val db = SoilDatabase.builder(this, enc.absolutePath)
                        .openHelperFactory(SoilCrypto.roomFactoryRawKey(key)).build()
                    try { db.openHelper.readableDatabase.query("SELECT count(*) FROM notebook").use { it.moveToFirst() } }
                    finally { db.close() }
                }
            } catch (e: Exception) { -1.0 }

            emit("deriveKey:            ${deriveMs.f()}ms (one-time per file)")
            emit("KeyMaterial 1st/2nd:  ${km1Ms.f()}ms / ${km2Ms.f()}ms (derive+persist / RAM hit)")
            emit("Keystore persisted:   $persistedOk")
            emit("verifyRawKey:         $verifyOk")
            emit("raw open + count:     ${rawMs.f()}ms (rows=$rawCount)")
            emit("Room raw-key open:    ${if (roomMs < 0) "FAIL" else roomMs.f() + "ms"}")
            val ok = persistedOk && verifyOk && rawCount >= 0 && roomMs >= 0
            emit("PRODUCTION PATH: ${if (ok) "OK ✓" else "PROBLEM ✗"}")
        } finally {
            com.notesprout.android.crypto.KeyMaterial.invalidate(this, fileId)
            cleanSoil(enc)
        }
    }

    // ── Timing / helpers ────────────────────────────────────────────────────

    private data class Reps(val cold: Double, val warm: Double)
    private data class Row(
        val name: String, val sizeKB: Long,
        val plain: Reps, val enc: Reps,
        val verifyMs: Double, val kdfMs: Double, val rawMs: Double, val rawOk: Boolean,
        val encryptMs: Double,
    ) {
        fun format(): String = buildString {
            append(name.padEnd(13))
            append(sizeKB.toString().padStart(6)); append("  ")
            append(plain.cold.f().padStart(7)); append(plain.warm.f().padStart(8)); append("  ")
            append(enc.cold.f().padStart(7)); append(enc.warm.f().padStart(8)); append("  ")
            append(verifyMs.f().padStart(7)); append(kdfMs.f().padStart(7)); append("  ")
            append((if (rawMs < 0) "FAIL" else rawMs.f()).padStart(7)); append(if (rawOk) " y" else " n"); append("  ")
            append(encryptMs.f().padStart(8))
        }
    }

    private fun header() =
        "notebook".padEnd(13) + "sizeKB".padStart(6) + "  " +
            "plainC".padStart(7) + "plainW".padStart(8) + "  " +
            "encC".padStart(7) + "encW".padStart(8) + "  " +
            "verify".padStart(7) + "kdf".padStart(7) + "  " +
            "rawKey".padStart(7) + " p" + "  encrypt".padStart(10)

    private fun title(s: String) { title = "Crypto Spike — $s" }

    private inline fun repsOf(block: () -> Double): Reps {
        val samples = (0 until REPS).map { block() }
        val cold = samples.first()
        val warm = samples.drop(1).minOrNull() ?: cold
        return Reps(cold, warm)
    }

    private inline fun measureMs(block: () -> Unit): Double {
        val t0 = System.nanoTime()
        block()
        return (System.nanoTime() - t0) / 1_000_000.0
    }

    private fun copyClean(src: File, dst: File) {
        cleanSoil(dst)
        src.copyTo(dst, overwrite = true)
    }

    private fun cleanSoil(f: File) {
        f.delete()
        File("${f.absolutePath}-wal").delete()
        File("${f.absolutePath}-shm").delete()
        File("${f.absolutePath}-journal").delete()
    }

    private fun isPlaintextSqlite(f: File): Boolean {
        if (f.length() < 16) return false
        // SQLite's 16-byte magic is "SQLite format 3 ". Compare the unambiguous 15-char prefix.
        val magic = "SQLite format 3".toByteArray(Charsets.US_ASCII)
        val head = ByteArray(magic.size)
        return runCatching { f.inputStream().use { it.read(head) }; head.contentEquals(magic) }.getOrDefault(false)
    }

    private fun readSalt(encFile: File): ByteArray {
        val salt = ByteArray(KDF_SALT_LEN)
        encFile.inputStream().use { it.read(salt) }
        return salt
    }

    /** Manual PBKDF2-HMAC-SHA512 over UTF-8 password bytes — byte-exact to SQLCipher's default KDF. */
    private fun pbkdf2HmacSha512(password: ByteArray, salt: ByteArray, iterations: Int, dkLen: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA512").apply { init(SecretKeySpec(password, "HmacSHA512")) }
        val hLen = mac.macLength
        val out = ByteArray(dkLen)
        var offset = 0
        var block = 1
        while (offset < dkLen) {
            // U1 = PRF(P, S || INT_32_BE(block))
            mac.update(salt)
            mac.update(byteArrayOf((block ushr 24).toByte(), (block ushr 16).toByte(), (block ushr 8).toByte(), block.toByte()))
            var u = mac.doFinal()
            val t = u.copyOf()
            for (it in 2..iterations) {
                u = mac.doFinal(u)
                for (k in t.indices) t[k] = (t[k].toInt() xor u[k].toInt()).toByte()
            }
            val n = minOf(hLen, dkLen - offset)
            System.arraycopy(t, 0, out, offset, n)
            offset += n
            block++
        }
        return out
    }

}

// File-level so the nested Row class can use them too (member extensions of the Activity
// are out of scope inside a nested class).
private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
private fun Double.f(): String = if (this < 0) "-" else "%.1f".format(this)
private fun Double.f1(): String = if (this < 0) "-" else "%.1f".format(this)
