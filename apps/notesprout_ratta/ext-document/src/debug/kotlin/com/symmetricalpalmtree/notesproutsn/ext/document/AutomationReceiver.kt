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
 * - `find_open --es query <q>` — opens the find bar on the query; replies `OK:<matches>`.
 * - `find_next` / `find_prev` — steps; replies `OK:<count label>` (`OK:none` when it reads empty).
 * - `replace_all --es with <r>` — replies `OK:<replaced>`.
 * - `find_close` · `reflow` · `undo` (the editor's own Ctrl+Z).
 * - `word_count` — `words=<n> chars=<m>`, selection-aware like the toast.
 * - `set_size --ef sp <f>` · `get_size` — the size **preference** in sp, not the view's px.
 * - `flip --ei dir <-1|1>` — a page flip (M6). Replies `OK` when it *started*: the flip pushes the
 *   outgoing page and reads the incoming one, so poll `get_state` / `page_label` after it.
 * - `bring_in --ei mode <0|1>` — Replace (0) / Append (1), the sheet's two rows without the sheet.
 *   Also asynchronous: poll `get_text` / `source_label`.
 * - `page_label` — the header's `n / m` alone (`-` when the target is not a page).
 * - `source_label` — the source strip's line (empty in the notebook scope with no merge behind it).
 * - `get_scope` — `0` (this page's document) or `1` (the notebook document) (M7).
 * - `toggle_scope` — the header toggle's tap. Replies `OK` when it *started*: entering the notebook
 *   scope can auto-merge the whole notebook, so poll `get_scope` / `get_state` after it.
 * - `merge --ei mode <0|1>` — Replace (0) / Append (1) of the notebook-wide merge. Notebook scope
 *   only; replies `ERR:not notebook scope` otherwise, and is asynchronous like `bring_in`.
 * - `show_pages` — the text document's "Show pages" button (M8). Replies `ERR:no show pages button`
 *   when it is not on screen (not a text document, or not the notebook scope); otherwise it leaves
 *   the screen, exactly as `close` does, after telling the host to open the pages.
 * - `rename --es text <name>` (or `--es file …`, the `set_text` mechanism — the Supernote swallows
 *   nothing here, but a name with spaces or non-ASCII travels better in a file) — renames the
 *   notebook without the dialog. Text documents only; replies `ERR:rename refused` when it did not
 *   start, and is asynchronous: poll `get_title`.
 * - `get_title` — the header's title alone.
 *
 * **It never logs the document, and never the find query either.** Result data carries text back to
 * the shell because that is the whole point of `get_text`; nothing is written to logcat on any path
 * here.
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

        "find_open" -> "OK:" + peer.findOpen(intent.getStringExtra("query").orEmpty())

        "find_next" -> "OK:" + peer.findStep(backwards = false).ifEmpty { "none" }

        "find_prev" -> "OK:" + peer.findStep(backwards = true).ifEmpty { "none" }

        "replace_all" -> "OK:" + peer.findReplaceAll(intent.getStringExtra("with").orEmpty())

        "find_close" -> {
            peer.findClose(); "OK"
        }

        "reflow" -> {
            peer.reflow(); "OK"
        }

        "word_count" -> peer.wordCount().let { (words, chars) -> "words=$words chars=$chars" }

        "undo" -> {
            peer.undo(); "OK"
        }

        "set_size" -> {
            peer.setTextSize(intent.getFloatExtra("sp", peer.textSize())); "OK"
        }

        "get_size" -> peer.textSize().toString()

        "flip" -> {
            peer.flip(intent.getIntExtra("dir", 0)); "OK"
        }

        "bring_in" -> {
            peer.bringIn(intent.getIntExtra("mode", 0)); "OK"
        }

        "page_label" -> peer.pageLabel().ifEmpty { "-" }

        "source_label" -> peer.sourceLabel()

        "get_scope" -> peer.scope().toString()

        "toggle_scope" -> {
            peer.toggleScope(); "OK"
        }

        "merge" -> if (peer.merge(intent.getIntExtra("mode", 0))) "OK" else "ERR:not notebook scope"

        "show_pages" -> if (peer.showPages()) "OK" else "ERR:no show pages button"

        "rename" -> if (peer.rename(payload(intent))) "OK" else "ERR:rename refused"

        "get_title" -> peer.title()

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
