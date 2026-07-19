package com.notesprout.android

import android.app.Activity
import android.graphics.Color
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.notesprout.android.crypto.EncryptionInfo
import com.notesprout.android.crypto.KeyScope
import com.notesprout.android.crypto.PassphrasePrompt
import com.notesprout.android.crypto.SoilMigrator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

/**
 * The keying chooser shown when exporting an *encrypted* notebook to a portable `.soil`.
 *
 * Operates only on the already-packaged temp copy [packaged] (in cacheDir) — the Garden original is
 * never touched. Options:
 *  - Keep current encryption → export the copy as-is (GLOBAL keeps this device's global passphrase;
 *    NOTEBOOK keeps its own).
 *  - Remove encryption       → decrypt the copy to plaintext (after a warning).
 *  - Set a new passphrase     → re-key the copy to a passphrase the user types.
 *
 * Key handling differs by caller, so [resolveCurrentKey] is injected: from MainActivity it returns
 * the device global passphrase (GLOBAL) or prompts for the notebook passphrase (NOTEBOOK); from an
 * open NotebookActivity it returns the already-unlocked key. A plaintext source skips the chooser.
 * A null from [resolveCurrentKey] (cancelled / wrong passphrase) abandons the export.
 */
object SoilExportKeying {

    fun chooseAndApply(
        activity: Activity,
        scope: CoroutineScope,
        packaged: File,
        info: EncryptionInfo,
        notebookName: String,
        resolveCurrentKey: suspend () -> String?,
        onReady: (File) -> Unit,
    ) {
        if (!info.encrypted) { onReady(packaged); return }

        val keepLabel = if (info.keyScope == KeyScope.NOTEBOOK)
            "Keep encryption (current notebook passphrase)"
        else
            "Keep encryption (this device's global passphrase)"

        ActionSheetDialog(activity)
            .title("Export “$notebookName”")
            .addAction(null, keepLabel) { onReady(packaged) }
            .addAction(null, "Remove encryption (plaintext)") {
                scope.launch {
                    if (!confirmPlaintext(activity)) { packaged.deleteQuietly(); return@launch }
                    val key = resolveCurrentKey() ?: run { packaged.deleteQuietly(); return@launch }
                    if (transform(activity) { SoilMigrator.decryptInPlace(packaged, key) }) onReady(packaged)
                    else packaged.deleteQuietly()
                }
            }
            .addAction(null, "Set a new passphrase…") {
                scope.launch {
                    val key = resolveCurrentKey() ?: run { packaged.deleteQuietly(); return@launch }
                    val newPass = PassphrasePrompt.promptForPassphrase(
                        activity,
                        title = "Export passphrase",
                        message = "Set a passphrase for the exported copy. You'll need it to open the file " +
                            "later (or a device that uses it as its global passphrase).",
                        confirm = true,
                    ) ?: run { packaged.deleteQuietly(); return@launch }
                    if (transform(activity) { SoilMigrator.rekeyInPlace(packaged, key, newPass) }) onReady(packaged)
                    else packaged.deleteQuietly()
                }
            }
            .addAction(null, "Cancel") { packaged.deleteQuietly() }
            .show()
    }

    private suspend fun confirmPlaintext(activity: Activity): Boolean =
        suspendCancellableCoroutine { cont ->
            val d = AlertDialog.Builder(activity)
                .setTitle("Export without encryption?")
                .setMessage(
                    "The exported file will be unencrypted — anyone with the file can read its " +
                    "contents. Your library copy stays encrypted."
                )
                .setPositiveButton("Export unencrypted") { _, _ -> if (cont.isActive) cont.resume(true) }
                .setNegativeButton("Cancel") { _, _ -> if (cont.isActive) cont.resume(false) }
                .setOnCancelListener { if (cont.isActive) cont.resume(false) }
                .create()
            d.show()
            d.window?.setElevation(0f)
            d.window?.setBackgroundDrawableResource(R.drawable.shape_dialog_bordered)
            cont.invokeOnCancellation { d.dismiss() }
        }

    /** Run [block] on IO behind a modal "Exporting…" spinner. Returns true on success. */
    private suspend fun transform(activity: Activity, block: suspend () -> Unit): Boolean {
        val tv = TextView(activity).apply {
            text = "Exporting…"
            setPadding(64, 48, 64, 48)
            setTextColor(Color.BLACK)
            textSize = 16f
        }
        val dialog = AlertDialog.Builder(activity).setView(tv).setCancelable(false).create()
        dialog.show()
        dialog.window?.setElevation(0f)
        dialog.window?.setBackgroundDrawableResource(R.drawable.shape_dialog_bordered)
        return try {
            withContext(Dispatchers.IO) { block() }
            dialog.dismiss()
            true
        } catch (e: Exception) {
            dialog.dismiss()
            Toast.makeText(activity, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
            false
        }
    }

    private fun File.deleteQuietly() { runCatching { delete() } }
}
