package com.notesprout.android

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.Spanned
import android.text.TextWatcher
import android.util.Log
import androidx.appcompat.widget.AppCompatEditText
import com.notesprout.android.core.DocumentPreferences
import com.notesprout.android.core.Slog
import com.notesprout.android.core.proofread.ProofreadCheck
import com.notesprout.android.core.proofread.ProofreadDirty
import com.notesprout.android.core.proofread.SpellEngine
import com.notesprout.android.core.proofread.WordSpan
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Marks one flagged word in the editor's buffer. Carries no styling of its own — the editor draws
 * the dashed underline in its own `onDraw`, where a [android.graphics.DashPathEffect] is possible
 * and a `CharacterStyle` is not — but as an Editable span it rides every edit, so a flag stays on
 * its word while text moves around it.
 */
class ProofreadFlagSpan

/**
 * Spell checking for the document editor — the thin Android layer over `core/proofread`.
 *
 * **Debounced, never per-keystroke** (see docs and the proofread plan): edits accumulate in a
 * [ProofreadDirty] range and are re-checked after [CHECK_DELAY_MS] of typing idle, expanded to
 * whole lines by [ProofreadCheck.lineRegion]. The check itself runs off the main thread against a
 * snapshot; a result that arrives after further typing is discarded and its region folded back
 * into the dirty range, so no word is silently left unchecked.
 *
 * Spans are **diffed, not rewritten**: a word already flagged at the same offsets keeps its span.
 * On e-ink every needless invalidate is a visible flash, so an unchanged screen costs nothing.
 *
 * Tapping a flagged word opens an [ActionSheetDialog] of suggestions plus *Ignore for now* —
 * session-scoped by design; a durable "Add to dictionary" arrives with Phase 3's user dictionary,
 * and a session-only add would lie about its lifetime.
 *
 * The on/off state is global ([DocumentPreferences], default on). While off — or while the editor
 * is in Preview, which pauses the timer — nothing runs and no spans exist. The dictionary is only
 * loaded once proofread is actually wanted: it holds a large in-memory index, and a user who
 * turned the feature off should not pay for it.
 */
class ProofreadController(
    private val editor: AppCompatEditText,
    private val scope: CoroutineScope,
    private val openDictionary: () -> InputStream,
) {
    companion object {
        private const val TAG = "Proofread"

        /** Typing-idle window before the edited region is re-checked. */
        private const val CHECK_DELAY_MS = 1500L
    }

    private val context: Context get() = editor.context

    /** Whether proofread is on — global preference, read once and kept in step by [setEnabled]. */
    var enabled: Boolean = DocumentPreferences.proofreadEnabled(editor.context)
        private set

    /** True once the dictionary is loaded and checks can actually run. */
    val ready: Boolean get() = engine != null

    private var engine: SpellEngine? = null
    private var engineRequested = false

    /** Words the user chose to ignore, normalized lowercase. This editor session only. */
    private val ignored = mutableSetOf<String>()

    private val handler = Handler(Looper.getMainLooper())
    private val checkTick = Runnable { runPendingCheck() }

    private val dirty = ProofreadDirty()
    private var dirtyWholeDocument = false

    /** True while the editor is in Preview — checks hold until it returns to Write. */
    private var paused = false

    /** Bumped on every text change; a background result from an older generation is stale. */
    private var generation = 0

    init {
        editor.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                // Removing a fence or backtick changes what is code *below* it, so the outgoing
                // text has to be inspected here — it no longer exists in onTextChanged.
                if (count > 0 && s != null &&
                    ProofreadCheck.affectsWholeDocument(s.subSequence(start, start + count))
                ) dirtyWholeDocument = true
            }

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                generation++
                if (count > 0 && s != null &&
                    ProofreadCheck.affectsWholeDocument(s.subSequence(start, start + count))
                ) dirtyWholeDocument = true
                dirty.note(start, before, count)
            }

            override fun afterTextChanged(s: Editable?) {
                if (enabled && !paused && engine != null) schedule()
            }
        })
    }

    /** Called once from `onCreate`. Loads the dictionary only when the feature is on. */
    fun start() {
        if (enabled) loadEngine()
    }

    /** Nothing may fire after the activity is gone; the scope cancels the rest. */
    fun dispose() {
        handler.removeCallbacks(checkTick)
    }

    /**
     * Turn the pass on or off, remembered globally. On clears nothing and checks everything; off
     * cancels whatever is pending and takes every flag off the screen.
     */
    fun setEnabled(on: Boolean) {
        if (enabled == on) return
        enabled = on
        DocumentPreferences.saveProofreadEnabled(context, on)
        Slog.d(TAG) { "Proofread ${if (on) "on" else "off"}" }
        if (on) {
            loadEngine()
            checkDocument()
        } else {
            clearPending()
            removeAllFlags()
        }
    }

    /** Preview pauses the timer; returning to Write resumes anything left owing. */
    fun setPaused(on: Boolean) {
        paused = on
        if (on) {
            handler.removeCallbacks(checkTick)
        } else if (enabled && engine != null && (dirtyWholeDocument || !dirty.isEmpty)) {
            schedule()
        }
    }

    /**
     * Full pass over the current text — the open, page-flip, and "Check document" path. A call
     * before the dictionary is ready is not lost: loading completes into exactly this.
     */
    fun checkDocument() {
        if (!enabled) return
        clearPending()
        if (engine == null) {
            loadEngine()
            return
        }
        val length = editor.text?.length ?: return
        check(ProofreadCheck.Region(0, length))
    }

    /**
     * The editor's tap hook: a tap on a flagged word opens the suggestion popup. Suggestions are
     * looked up off the main thread — `Verbosity.All` over the whole dictionary is popup-only
     * cost, but it is not free on an e-ink CPU.
     */
    fun onTap(offset: Int) {
        if (!enabled) return
        val engine = engine ?: return
        val text = editor.text ?: return
        val span = text.getSpans(offset, offset, ProofreadFlagSpan::class.java).firstOrNull() ?: return
        val start = text.getSpanStart(span)
        val end = text.getSpanEnd(span)
        if (start < 0 || end <= start) return
        val word = text.subSequence(start, end).toString()
        scope.launch {
            val suggestions = withContext(Dispatchers.Default) { engine.suggestions(word) }
            // Re-resolve before showing: the span may have moved — or died — while we looked up.
            val current = editor.text ?: return@launch
            val s = current.getSpanStart(span)
            val e = current.getSpanEnd(span)
            if (s < 0 || e <= s || current.subSequence(s, e).toString() != word) return@launch
            showPopup(span, word, suggestions)
        }
    }

    // ── The pass ──────────────────────────────────────────────────────────────

    private fun loadEngine() {
        if (engineRequested) return
        engineRequested = true
        scope.launch {
            // A dictionary that cannot load must cost the user nothing but spell checking — this
            // runs under the editor, and an escaped exception here takes the whole screen down.
            val loaded = try {
                SpellEngine.shared(openDictionary)
            } catch (e: CancellationException) {
                throw e // the activity is going away, not the dictionary failing
            } catch (e: Exception) {
                Log.e(TAG, "Dictionary failed to load; proofread stays quiet", e)
                engineRequested = false // an explicit "Check document" may retry
                return@launch
            }
            engine = loaded
            Slog.d(TAG) { "Dictionary ready: ${loaded.wordCount} words" }
            if (enabled) checkDocument()
        }
    }

    private fun schedule() {
        handler.removeCallbacks(checkTick)
        handler.postDelayed(checkTick, CHECK_DELAY_MS)
    }

    private fun clearPending() {
        handler.removeCallbacks(checkTick)
        dirty.clear()
        dirtyWholeDocument = false
    }

    private fun runPendingCheck() {
        if (!enabled || paused) return
        val text = editor.text ?: return
        val whole = dirtyWholeDocument
        val dirtyStart = dirty.start
        val dirtyEnd = dirty.end
        val hadDirty = !dirty.isEmpty
        clearPending()
        if (!whole && !hadDirty) return
        val snapshot = text.toString()
        val region = if (whole) {
            ProofreadCheck.Region(0, snapshot.length)
        } else {
            ProofreadCheck.lineRegion(snapshot, dirtyStart, dirtyEnd)
        }
        check(region)
    }

    private fun check(region: ProofreadCheck.Region) {
        val engine = engine ?: return
        val snapshot = editor.text?.toString() ?: return
        val clamped = ProofreadCheck.Region(
            region.start.coerceIn(0, snapshot.length),
            region.end.coerceIn(region.start.coerceIn(0, snapshot.length), snapshot.length),
        )
        val gen = generation
        scope.launch(Dispatchers.Default) {
            val flags = ProofreadCheck.misspelled(snapshot, clamped, engine::isKnown) {
                normalize(it) in ignored
            }
            withContext(Dispatchers.Main) {
                if (gen != generation) {
                    // Typing moved on under us. The region's words still owe a check — fold it
                    // back in (offsets are near enough; lineRegion re-squares them) and re-arm.
                    dirty.merge(clamped.start, clamped.end)
                    if (enabled && !paused) schedule()
                    return@withContext
                }
                applyFlags(clamped, flags)
            }
        }
    }

    /**
     * Installs a region's fresh result, touching only spans that actually changed: a word still
     * misspelled at the same offsets keeps its span, so a pass that finds nothing new repaints
     * nothing.
     */
    private fun applyFlags(region: ProofreadCheck.Region, flags: List<WordSpan>) {
        val text = editor.text ?: return
        val fresh = HashSet<Long>(flags.size * 2)
        for (f in flags) fresh.add(spanKey(f.start, f.end))
        val kept = HashSet<Long>()
        var changed = false
        for (span in text.getSpans(region.start, region.end, ProofreadFlagSpan::class.java)) {
            val s = text.getSpanStart(span)
            val e = text.getSpanEnd(span)
            // getSpans also returns spans merely *touching* the range — a word ending exactly at
            // the region edge belongs to the neighbouring lines and is not this pass's to judge.
            if (e <= region.start || s >= region.end) continue
            val key = spanKey(s, e)
            if (key in fresh) {
                kept.add(key)
            } else {
                text.removeSpan(span)
                changed = true
            }
        }
        for (f in flags) {
            if (spanKey(f.start, f.end) in kept) continue
            text.setSpan(ProofreadFlagSpan(), f.start, f.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            changed = true
        }
        // The spans carry no style of their own, so the repaint has to be asked for.
        if (changed) editor.invalidate()
        Slog.d(TAG) { "Checked [${region.start}, ${region.end}): ${flags.size} flagged" }
    }

    // ── Popup ─────────────────────────────────────────────────────────────────

    private fun showPopup(span: ProofreadFlagSpan, word: String, suggestions: List<String>) {
        val sheet = ActionSheetDialog(context)
        sheet.title(if (suggestions.isEmpty()) "No suggestions for “$word”" else "“$word”")
        for (suggestion in suggestions) {
            sheet.addAction(null, suggestion) { replace(span, suggestion) }
        }
        sheet.addAction(R.drawable.ic_eye_off, "Ignore for now") { ignore(word) }
        sheet.show()
    }

    /**
     * Replace the flagged word through the `Editable` — the same route the format bar takes, so
     * the editor's own Ctrl+Z can take it back.
     */
    private fun replace(span: ProofreadFlagSpan, suggestion: String) {
        val text = editor.text ?: return
        val start = text.getSpanStart(span)
        val end = text.getSpanEnd(span)
        if (start < 0 || end <= start) return
        text.removeSpan(span)
        text.replace(start, end, suggestion)
        editor.setSelection((start + suggestion.length).coerceIn(0, text.length))
        Slog.d(TAG) { "Replaced a flagged word (${suggestion.length} chars in)" }
    }

    /** Ignore [word] for this session and take its flags — all of them — off the screen. */
    private fun ignore(word: String) {
        ignored.add(normalize(word))
        val text = editor.text ?: return
        var changed = false
        for (span in text.getSpans(0, text.length, ProofreadFlagSpan::class.java)) {
            val s = text.getSpanStart(span)
            val e = text.getSpanEnd(span)
            if (s < 0 || e <= s) continue
            if (normalize(text.subSequence(s, e).toString()) == normalize(word)) {
                text.removeSpan(span)
                changed = true
            }
        }
        if (changed) editor.invalidate()
        Slog.d(TAG) { "Ignoring a word for this session (${ignored.size} ignored)" }
    }

    private fun removeAllFlags() {
        generation++ // an in-flight result must not land after this
        val text = editor.text ?: return
        val spans = text.getSpans(0, text.length, ProofreadFlagSpan::class.java)
        if (spans.isEmpty()) return
        for (span in spans) text.removeSpan(span)
        editor.invalidate()
    }

    /** The engine's own normalization: knowledge is lowercase, and ’ counts as '. */
    private fun normalize(word: String): String = word.replace('’', '\'').lowercase()

    private fun spanKey(start: Int, end: Int): Long = (start.toLong() shl 32) or end.toLong()
}
