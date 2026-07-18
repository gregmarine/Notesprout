package com.notesprout.android.debug

import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.notesprout.android.crypto.PassphraseStore
import com.notesprout.android.crypto.RawKeyDerivation
import com.notesprout.android.data.hwr.HwrTrainingDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Debug-only: reveals this device's global passphrase (a.k.a. recovery key) and the derived index
 * key so the now-encrypted `notesprout.db` can still be inspected with the sqlcipher CLI. Shown
 * on-screen only (selectable), never logged. Debug source set — never ships.
 *
 * adb shell am start -n com.notesprout.android.dev/com.notesprout.android.debug.DebugKeyActivity
 */
class DebugKeyActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pass = PassphraseStore.getGlobalPassphrase(this) ?: "(no global passphrase set yet)"
        val idxFile = File(getExternalFilesDir(null), "notesprout.db")
        val idxKeyHex = runCatching {
            "x'" + RawKeyDerivation.deriveKey(idxFile, pass).joinToString("") { "%02x".format(it) } + "'"
        }.getOrElse { "(unavailable: ${it.message})" }

        val body = buildString {
            appendLine("GLOBAL PASSPHRASE / RECOVERY KEY:")
            appendLine(pass)
            appendLine()
            appendLine("Inspect the encrypted index (pull notesprout.db, then):")
            appendLine("  sqlcipher notesprout.db")
            appendLine("  PRAGMA key = '$pass';")
            appendLine("  SELECT count(*) FROM objects;")
            appendLine()
            appendLine("Index raw key (skips KDF; PRAGMA key = \"<this>\"):")
            appendLine(idxKeyHex)
        }
        val tv = TextView(this).apply {
            setTextIsSelectable(true)
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(24, 24, 24, 24)
            text = body
        }
        setContentView(ScrollView(this).apply { addView(tv) })

        // Force + verify training.db encryption (touches the DAO off the UI thread, then re-reads the header).
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    HwrTrainingDatabase.dao(this@DebugKeyActivity) // forces build + migrate-if-plaintext
                    val f = File(filesDir, "hwr/training.db")
                    if (!f.exists() || f.length() < 15) {
                        "training.db: not materialized yet (no HWR training captured)"
                    } else {
                        val head = ByteArray(15)
                        f.inputStream().use { it.read(head) }
                        val plaintext = head.contentEquals("SQLite format 3".toByteArray(Charsets.US_ASCII))
                        "training.db: ${if (plaintext) "PLAINTEXT ✗" else "encrypted ✓"} (${f.length()} bytes)"
                    }
                }.getOrElse { "training.db check failed: ${it.message}" }
            }
            tv.append("\n\n$result")
        }
    }
}
