package com.symmetricalpalmtree.notesproutsn.ext.document

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * **Debug builds only** — the walk agent's hands.
 *
 * The Supernote's IME swallows `adb shell input text` and injected key events, so a scripted walk
 * cannot type one character into the editor. Every M4+ walk is about text: type, format, switch
 * mode, save, reopen and check what landed. This receiver is how those walks reach the buffer, and
 * it is the only reason it exists — it is declared in `src/debug/AndroidManifest.xml`, so a release
 * APK contains neither the receiver nor its manifest entry, and `EditorAutomation.peer` is never
 * assigned there either.
 *
 * Usage (the dev extension's package carries the `.dev` suffix):
 * ```
 * adb shell am broadcast -a com.symmetricalpalmtree.notesproutsn.ext.document.AUTOMATION \
 *   -p com.symmetricalpalmtree.notesproutsn.ext.document.dev --es cmd set_text --es text 'hello'
 * ```
 *
 * Commands (`--es cmd`):
 * - `set_text` / `append_text` — `--es text <t>` or `--es file /data/local/tmp/x.md` (UTF-8).
 * - `get_text` — the buffer as the broadcast's result data, or `LEN:<n>` when it is too big to
 *   travel through `am`'s reply.
 * - `get_state` — `mode=… caret=… dirty=… page=… chars=…`.
 * - `set_caret --ei pos <n>` · `mode --es mode write|preview` · `save` · `done` · `close`.
 *
 * **It never logs the document.** Result data carries text back to the shell because that is the
 * whole point of `get_text`; nothing is written to logcat on any path here.
 */
class AutomationReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val peer = EditorAutomation.peer
        if (peer == null) {
            reply("ERR:no editor")
            return
        }
        val result = try {
            onMain { run(peer, intent) } ?: "ERR:timeout"
        } catch (e: Exception) {
            "ERR:${e.javaClass.simpleName}"
        }
        reply(result)
    }

    private fun run(peer: AutomationPeer, intent: Intent): String = when (intent.getStringExtra("cmd")) {
        "set_text" -> {
            peer.setText(payload(intent), append = false); "OK"
        }

        "append_text" -> {
            peer.setText(payload(intent), append = true); "OK"
        }

        "get_text" -> peer.text().let { if (it.length <= MAX_REPLY_CHARS) it else "LEN:${it.length}" }

        "get_state" -> buildString {
            append("mode=").append(if (peer.isPreviewing()) "preview" else "write")
            append(" caret=").append(peer.caret())
            append(" dirty=").append(peer.isDirty())
            append(" page=").append(peer.pageLabel().ifEmpty { "-" })
            append(" chars=").append(peer.text().length)
        }

        "set_caret" -> {
            peer.setCaret(intent.getIntExtra("pos", 0)); "OK"
        }

        "mode" -> {
            peer.setPreviewing(intent.getStringExtra("mode") == "preview"); "OK"
        }

        "save" -> {
            peer.saveNow(); "OK"
        }

        "done" -> {
            peer.done(); "OK"
        }

        "close" -> {
            peer.close(); "OK"
        }

        else -> "ERR:unknown cmd"
    }

    /** `--es text` wins; `--es file` reads UTF-8 from the shell's own scratch directory. */
    private fun payload(intent: Intent): String {
        intent.getStringExtra("text")?.let { return it }
        val path = intent.getStringExtra("file") ?: return ""
        require(path.startsWith(SHELL_DIR)) { "file outside $SHELL_DIR" }
        return File(path).readText()
    }

    /** The peer is the live screen's: everything it touches lives on Main. */
    private fun <T> onMain(block: () -> T): T? {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val out = AtomicReference<T?>(null)
        val latch = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            try {
                out.set(block())
            } finally {
                latch.countDown()
            }
        }
        return if (latch.await(HOP_TIMEOUT_MS, TimeUnit.MILLISECONDS)) out.get() else null
    }

    /** Only an ordered broadcast has a result to set — `am broadcast` sends one and prints it. */
    private fun reply(data: String) {
        if (isOrderedBroadcast) resultData = data
    }

    private companion object {
        const val SHELL_DIR = "/data/local/tmp/"
        const val MAX_REPLY_CHARS = 50_000
        const val HOP_TIMEOUT_MS = 2_000L
    }
}
