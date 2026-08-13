package com.notesprout.android

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.Spanned
import android.text.TextWatcher
import android.util.Log
import android.widget.Toast
import androidx.appcompat.widget.AppCompatEditText
import com.notesprout.android.core.DocumentPreferences
import com.notesprout.android.core.Slog
import com.notesprout.android.data.index.NotesproutIndex
import com.notesprout.android.data.index.UserDictionaryEntity
import com.notesprout.android.core.proofread.GrammarFlag
import com.notesprout.android.core.proofread.GrammarRules
import com.notesprout.android.core.proofread.ProofreadCheck
import com.notesprout.android.core.proofread.ProofreadDirty
import com.notesprout.android.core.proofread.ProofreadTokenizer
import com.notesprout.android.core.proofread.SpellEngine
import com.notesprout.android.core.proofread.WordSpan
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
 * Marks one grammar finding — the dotted-underline sibling of [ProofreadFlagSpan]. Unlike a
 * spelling flag, whose word is looked up fresh at tap time, a grammar finding's message and fix
 * were computed against the text as it stood, so the span carries them — plus the [snippet] it
 * flagged: a tap on a span whose text has drifted mid-debounce is declined, and the imminent
 * re-check re-flags whatever still deserves it.
 */
class GrammarFlagSpan(
    val rule: String,
    val message: String,
    val replacement: String?,
    val snippet: String,
)

/**
 * Spell and grammar checking for the document editor — the thin Android layer over
 * `core/proofread`.
 *
 * **Debounced, never per-keystroke** (see docs and the proofread plan): edits accumulate in a
 * [ProofreadDirty] range and are re-checked after [CHECK_DELAY_MS] of typing idle, expanded to
 * whole lines by [ProofreadCheck.lineRegion]. The check itself runs off the main thread against a
 * snapshot; a result that arrives after further typing is discarded and the next tick escalates
 * to a whole-document pass, so no word is silently left unchecked.
 *
 * Spans are **diffed, not rewritten**: a word already flagged at the same offsets keeps its span.
 * On e-ink every needless invalidate is a visible flash, so an unchanged screen costs nothing.
 *
 * Tapping a flagged word opens an [ActionSheetDialog] of suggestions plus *Add to dictionary*
 * (durable — the `user_dictionary` table in the global index, so the host activity must be behind
 * [com.notesprout.android.core.IndexGuard]) and *Ignore for now* (this session only, by design).
 *
 * The same pass runs the [GrammarRules] essentials over the same region; findings get a dotted
 * underline ([GrammarFlagSpan]) and a popup with the finding's message, a one-tap *Fix* through
 * the `Editable` (Ctrl+Z-able) when the rule has a mechanical correction, and *Ignore for now*,
 * which mutes that rule-plus-snippet pair for the session.
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
    private val ignored = FilterSet()

    /** Muted grammar findings, keyed rule + snippet ([grammarKey]). This editor session only. */
    private val ignoredGrammar = FilterSet()

    /**
     * The durable user dictionary, mirrored in memory so the checking pass never reads the
     * database. Loaded with the engine (see [loadEngine]); adds and removes keep it in step.
     */
    private val userWords = FilterSet()

    /**
     * A set whose every mutation bumps [generation], so a pass launched before the change can
     * never land its now-wrong conclusions (re-installing a flag the user just dismissed). The
     * bump lives in the mutation seam rather than at each call site — a future site cannot
     * forget it. Main-thread only; background passes take [snapshot]s.
     */
    private inner class FilterSet {
        private val set = HashSet<String>()
        val size: Int get() = set.size
        fun snapshot(): HashSet<String> = HashSet(set)
        val isEmpty: Boolean get() = set.isEmpty()
        fun add(value: String) {
            if (set.add(value)) generation++
        }
        fun remove(value: String) {
            if (set.remove(value)) generation++
        }
        fun replaceAll(values: Collection<String>) {
            set.clear()
            set.addAll(values)
            generation++
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val checkTick = Runnable { runPendingCheck() }

    private val dirty = ProofreadDirty()
    private var dirtyWholeDocument = false

    /** True while the editor is in Preview — checks hold until it returns to Write. */
    private var paused = false

    /**
     * Bumped whenever what a running pass concluded may no longer hold — on every text change,
     * and on every ignore/dictionary mutation. A background result from an older generation is
     * stale and must not land: it would re-install a flag the user just dismissed.
     */
    private var generation = 0

    /** True while a tap's suggestion lookup is in flight — the second tap of a pile-up is dropped. */
    private var lookupInFlight = false

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
        val snapshot = editor.text?.toString() ?: return
        check(ProofreadCheck.Region(0, snapshot.length), snapshot)
    }

    /**
     * The editor's tap hook: a tap on a flagged word opens the suggestion popup. Suggestions are
     * looked up off the main thread — `Verbosity.All` over the whole dictionary is popup-only
     * cost, but it is not free on an e-ink CPU.
     */
    fun onTap(offset: Int) {
        if (!enabled) return
        // A tap that lands inside a selection belongs to the selection workflow (copy, cut,
        // drag handles) — the popup must not cover it.
        if (editor.hasSelection()) return
        val text = editor.text ?: return
        val span = text.getSpans(offset, offset, ProofreadFlagSpan::class.java).firstOrNull()
        val engine = engine
        if (span == null || engine == null) {
            // No spelling flag here — a grammar flag may still own the offset.
            text.getSpans(offset, offset, GrammarFlagSpan::class.java).firstOrNull()
                ?.let(::showGrammarPopup)
            return
        }
        if (lookupInFlight) return // one popup at a time; the sheet is modal once it shows
        lookupInFlight = true
        val start = text.getSpanStart(span)
        val end = text.getSpanEnd(span)
        if (start < 0 || end <= start) { lookupInFlight = false; return }
        val word = text.subSequence(start, end).toString()
        scope.launch {
            val suggestions = try {
                withContext(Dispatchers.Default) { engine.suggestions(word) }
            } finally {
                lookupInFlight = false
            }
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
            val loadStart = SystemClock.elapsedRealtime()
            // Neither a dictionary nor an index that cannot load may cost the user more than
            // spell checking — this runs under the editor, and an escaped exception here takes
            // the whole screen down. The user's words ride in with the engine: both must be
            // present before the first pass, or a word added yesterday would flash flagged
            // while the table was on its way. Independent work, so they load side by side.
            val (loaded, words) = try {
                coroutineScope {
                    val engineJob = async { SpellEngine.shared(openDictionary) }
                    val wordsJob = async { NotesproutIndex.userDictionaryDao().allWords() }
                    engineJob.await() to wordsJob.await()
                }
            } catch (e: CancellationException) {
                throw e // the activity is going away, not the load failing
            } catch (e: Exception) {
                Log.e(TAG, "Proofread failed to load and stays quiet", e)
                engineRequested = false // an explicit "Check document" may retry
                return@launch
            }
            userWords.replaceAll(words)
            engine = loaded
            Slog.d(TAG) {
                val ms = SystemClock.elapsedRealtime() - loadStart
                "Dictionary ready: ${loaded.wordCount} words + ${words.size} user words in ${ms}ms"
            }
            // The engine checks the moment the word map is up; the SymSpell suggestion index —
            // ~40 s of index building on an e-ink CPU — follows in the background, and the tap
            // popup says "suggestions are loading" until it lands. On the app scope, not this
            // editor's: the engine is process-shared, and a build cancelled by Back would start
            // from zero on every open — short sessions would never reach suggestions-ready.
            if (!loaded.suggestionsReady) NotesproutApplication.appScope.launch {
                try {
                    val t = SystemClock.elapsedRealtime()
                    loaded.loadSuggestionIndex()
                    Slog.d(TAG) {
                        "Suggestion index ready in ${SystemClock.elapsedRealtime() - t}ms"
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Suggestion index failed to build; popups stay suggestion-less", e)
                }
            }
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
        check(region, snapshot)
    }

    private fun check(region: ProofreadCheck.Region, snapshot: String) {
        val engine = engine ?: return
        val clamped = ProofreadCheck.Region(
            region.start.coerceIn(0, snapshot.length),
            region.end.coerceIn(region.start.coerceIn(0, snapshot.length), snapshot.length),
        )
        val gen = generation
        // The background pass must not read the live sets — Main mutates them with no
        // happens-before edge to Dispatchers.Default. Copies are a few hundred entries at most.
        val skipWords = ignored.snapshot().apply { addAll(userWords.snapshot()) }
        val mutedGrammar = ignoredGrammar.snapshot()
        scope.launch(Dispatchers.Default) {
            // One tokenizer pass feeds both checks — the mask sweep over the whole text is the
            // expensive part of a tick, and it would otherwise run twice.
            val skip = ProofreadTokenizer.skipMask(snapshot)
            val spans = ProofreadTokenizer.wordSpans(snapshot, skip)
            val flags = ProofreadCheck.misspelled(spans, clamped, engine::isKnown) {
                normalize(it) in skipWords
            }
            val allGrammar = GrammarRules.check(snapshot, clamped, skip, spans)
            val grammar = if (mutedGrammar.isEmpty()) allGrammar else allGrammar.filter {
                grammarKey(it.rule, snapshot.substring(it.start, it.end)) !in mutedGrammar
            }
            withContext(Dispatchers.Main) {
                if (gen != generation) {
                    // The document moved on under us, so the region's offsets no longer name the
                    // words that owe a check — an edit *before* the region shifts them wholesale.
                    // Escalating to a full pass is the only fold that cannot miss.
                    dirtyWholeDocument = true
                    if (enabled && !paused) schedule()
                    return@withContext
                }
                applyFlags(clamped, flags)
                applyGrammarFlags(clamped, grammar, snapshot)
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

    /**
     * The grammar twin of [applyFlags], with one more equality axis: a span at unchanged offsets
     * survives only if it still says the same thing — same rule, message, and fix — since a pass
     * can re-diagnose the same range.
     */
    private fun applyGrammarFlags(region: ProofreadCheck.Region, flags: List<GrammarFlag>, snapshot: String) {
        val text = editor.text ?: return
        val fresh = HashMap<Long, GrammarFlag>(flags.size * 2)
        for (f in flags) fresh[spanKey(f.start, f.end)] = f
        val kept = HashSet<Long>()
        var changed = false
        for (span in text.getSpans(region.start, region.end, GrammarFlagSpan::class.java)) {
            val s = text.getSpanStart(span)
            val e = text.getSpanEnd(span)
            if (e <= region.start || s >= region.end) continue
            val key = spanKey(s, e)
            val f = fresh[key]
            // The snippet is part of the identity: a same-length edit can leave offsets and
            // diagnosis unchanged while the text under the span drifts ("the the" → "the The"),
            // and a kept span with a stale snippet would decline every tap forever.
            if (f != null && f.rule == span.rule && f.message == span.message &&
                f.replacement == span.replacement &&
                span.snippet == snapshot.substring(f.start, f.end)
            ) {
                kept.add(key)
            } else {
                text.removeSpan(span)
                changed = true
            }
        }
        for (f in flags) {
            if (spanKey(f.start, f.end) in kept) continue
            text.setSpan(
                GrammarFlagSpan(f.rule, f.message, f.replacement, snapshot.substring(f.start, f.end)),
                f.start, f.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            changed = true
        }
        if (changed) editor.invalidate()
        Slog.d(TAG) { "Grammar [${region.start}, ${region.end}): ${flags.size} flagged" }
    }

    // ── Popup ─────────────────────────────────────────────────────────────────

    private fun showPopup(span: ProofreadFlagSpan, word: String, suggestions: List<String>) {
        val sheet = ActionSheetDialog(context)
        sheet.title(
            when {
                // Read at show time — an index that finished during the lookup stops apologizing.
                suggestions.isEmpty() && engine?.suggestionsReady != true ->
                    "“$word” — suggestions are loading"
                suggestions.isEmpty() -> "No suggestions for “$word”"
                else -> "“$word”"
            }
        )
        for (suggestion in suggestions) {
            sheet.addAction(null, suggestion) { replace(span, suggestion) }
        }
        sheet.addAction(R.drawable.ic_book, "Add to dictionary") { addToDictionary(word) }
        sheet.addAction(R.drawable.ic_eye_off, "Ignore for now") { ignore(word) }
        sheet.show()
    }

    /**
     * The grammar finding's popup: what the rule saw, its one-tap fix when it has one, and a
     * session-scoped mute. A span whose text no longer matches what was flagged is left silent —
     * the edit that moved it has a re-check already owing.
     */
    private fun showGrammarPopup(span: GrammarFlagSpan) {
        val text = editor.text ?: return
        val start = text.getSpanStart(span)
        val end = text.getSpanEnd(span)
        if (start < 0 || end <= start) return
        if (text.subSequence(start, end).toString() != span.snippet) return
        val sheet = ActionSheetDialog(context)
        sheet.title("${span.message} — “${span.snippet}”")
        span.replacement?.let { fix ->
            sheet.addAction(R.drawable.ic_check, "Fix: “$fix”") { replace(span, fix) }
        }
        sheet.addAction(R.drawable.ic_eye_off, "Ignore for now") { ignoreGrammar(span) }
        sheet.show()
    }

    /**
     * The minimal dictionary manager: every saved word, tap one to remove it. Reads the table
     * fresh rather than trusting [userWords] — that set only exists once the engine has loaded,
     * and this list must be truthful even before then.
     */
    fun promptUserDictionary() {
        scope.launch {
            val words = try {
                NotesproutIndex.userDictionaryDao().allWords()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "User dictionary unavailable", e)
                Toast.makeText(context, "The dictionary is not available right now", Toast.LENGTH_SHORT).show()
                return@launch
            }
            if (words.isEmpty()) {
                Toast.makeText(context, "No words added to the dictionary yet", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val sheet = ActionSheetDialog(context)
            sheet.title("User dictionary — tap a word to remove it")
            for (word in words) {
                sheet.addAction(R.drawable.ic_trash, word) { removeFromDictionary(word) }
            }
            sheet.show()
        }
    }

    /**
     * Make [word] correct from now on, everywhere: into the in-memory set for this session's
     * passes, into the global index for every future one. The write goes through the app scope —
     * an add followed immediately by Back must not be cancelled with the activity.
     */
    private fun addToDictionary(word: String) {
        val w = normalize(word)
        userWords.add(w) // the FilterSet bump keeps any in-flight pass from re-flagging it
        unflag(w)
        NotesproutApplication.appScope.launch {
            try {
                NotesproutIndex.userDictionaryDao().add(UserDictionaryEntity(w, System.currentTimeMillis()))
            } catch (e: Exception) {
                Log.e(TAG, "User dictionary write failed; the word holds for this session", e)
            }
        }
        Slog.d(TAG) { "Added a word to the user dictionary (${userWords.size} in memory)" }
    }

    /** Take [word] back out; whatever the document owes in flags, the fresh pass repays. */
    private fun removeFromDictionary(word: String) {
        userWords.remove(word)
        NotesproutApplication.appScope.launch {
            try {
                NotesproutIndex.userDictionaryDao().remove(word)
            } catch (e: Exception) {
                Log.e(TAG, "User dictionary delete failed; the word returns next session", e)
            }
        }
        Toast.makeText(context, "Removed “$word”", Toast.LENGTH_SHORT).show()
        checkDocument()
        Slog.d(TAG) { "Removed a word from the user dictionary" }
    }

    /**
     * Replace a flagged range — spelling or grammar span alike — through the `Editable`, the same
     * route the format bar takes, so the editor's own Ctrl+Z can take it back.
     */
    private fun replace(span: Any, suggestion: String) {
        val text = editor.text ?: return
        val start = text.getSpanStart(span)
        val end = text.getSpanEnd(span)
        if (start < 0 || end <= start) return
        text.removeSpan(span)
        text.replace(start, end, suggestion)
        editor.setSelection((start + suggestion.length).coerceIn(0, text.length))
        Slog.d(TAG) { "Replaced a flagged word (${suggestion.length} chars in)" }
    }

    /**
     * Mute this finding for the session — every flag saying the same thing about the same text,
     * not just the tapped one, comes off the screen with it.
     */
    private fun ignoreGrammar(span: GrammarFlagSpan) {
        val key = grammarKey(span.rule, span.snippet)
        ignoredGrammar.add(key)
        val text = editor.text ?: return
        var changed = false
        for (sp in text.getSpans(0, text.length, GrammarFlagSpan::class.java)) {
            if (grammarKey(sp.rule, sp.snippet) == key) {
                text.removeSpan(sp)
                changed = true
            }
        }
        if (changed) editor.invalidate()
        Slog.d(TAG) { "Muted a grammar finding for this session (${ignoredGrammar.size} muted)" }
    }

    private fun grammarKey(rule: String, snippet: String): String = "$rule:${snippet.lowercase()}"

    /** Ignore [word] for this session and take its flags — all of them — off the screen. */
    private fun ignore(word: String) {
        val w = normalize(word)
        ignored.add(w)
        unflag(w)
        Slog.d(TAG) { "Ignoring a word for this session (${ignored.size} ignored)" }
    }

    /** Take every flag whose word normalizes to [w] off the screen. */
    private fun unflag(w: String) {
        val text = editor.text ?: return
        var changed = false
        for (span in text.getSpans(0, text.length, ProofreadFlagSpan::class.java)) {
            val s = text.getSpanStart(span)
            val e = text.getSpanEnd(span)
            if (s < 0 || e <= s) continue
            if (normalize(text.subSequence(s, e).toString()) == w) {
                text.removeSpan(span)
                changed = true
            }
        }
        if (changed) editor.invalidate()
    }

    private fun removeAllFlags() {
        generation++ // an in-flight result must not land after this
        val text = editor.text ?: return
        val spelling = text.getSpans(0, text.length, ProofreadFlagSpan::class.java)
        val grammar = text.getSpans(0, text.length, GrammarFlagSpan::class.java)
        if (spelling.isEmpty() && grammar.isEmpty()) return
        for (span in spelling) text.removeSpan(span)
        for (span in grammar) text.removeSpan(span)
        editor.invalidate()
    }

    /** The engine's own normalization: knowledge is lowercase, and ’ counts as '. */
    private fun normalize(word: String): String = SpellEngine.normalizeWord(word)

    private fun spanKey(start: Int, end: Int): Long = (start.toLong() shl 32) or end.toLong()
}
