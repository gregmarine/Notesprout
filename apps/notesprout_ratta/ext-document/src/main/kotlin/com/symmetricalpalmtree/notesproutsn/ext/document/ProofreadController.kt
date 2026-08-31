package com.symmetricalpalmtree.notesproutsn.ext.document

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.Spanned
import android.text.TextWatcher
import android.util.Log
import com.symmetricalpalmtree.notesproutsn.core.Slog
import com.symmetricalpalmtree.notesproutsn.ext.document.proofread.GrammarFlag
import com.symmetricalpalmtree.notesproutsn.ext.document.proofread.GrammarRules
import com.symmetricalpalmtree.notesproutsn.ext.document.proofread.ProofreadCheck
import com.symmetricalpalmtree.notesproutsn.ext.document.proofread.ProofreadDirty
import com.symmetricalpalmtree.notesproutsn.ext.document.proofread.ProofreadTokenizer
import com.symmetricalpalmtree.notesproutsn.ext.document.proofread.SpellEngine
import com.symmetricalpalmtree.notesproutsn.ext.document.proofread.WordSpan
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream

/**
 * Writes that must land even if the screen goes away in the same breath — the on/off toggle and both
 * user-dictionary edits. A word added and then dismissed with Back is still a word the writer
 * vouched for, and a scope tied to the Activity would cancel it on the way out.
 */
private val proofreadWrites = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/**
 * The SymSpell suggestion index's build — tens of seconds of work on an e-ink CPU, and the engine it
 * completes is process-shared. og hands this to its Application scope; this module has no
 * Application class, so the scope is the process's own. Tying it to an editor would restart it from
 * zero on every open, and a writer who works in short sessions would never once reach
 * suggestions-ready.
 */
private val proofreadIndexing = CoroutineScope(SupervisorJob() + Dispatchers.Default)

/**
 * Spell and grammar checking for the document editor (arc 19 / M10) — the thin Android layer over
 * `proofread/`, and the only place in this app that has one.
 *
 * **Debounced, never per-keystroke:** edits accumulate in a [ProofreadDirty] range and are re-checked
 * after [CHECK_DELAY_MS] of typing idle, expanded to whole lines by [ProofreadCheck.lineRegion]. The
 * check itself runs off the main thread against a snapshot; a result that arrives after further
 * typing is discarded and the next tick escalates to a whole-document pass, so no word is silently
 * left unchecked.
 *
 * **Spans are diffed, not rewritten:** a word already flagged at the same offsets keeps its span. On
 * e-ink every needless invalidate is a visible flash, so an unchanged screen costs nothing.
 *
 * Tapping a flagged word opens a [ProofreadSheets] popup of suggestions plus *Add to dictionary*
 * (durable — [EditorPrefs]' user dictionary in the host's extension store) and *Ignore for now*
 * (this session only, by design). The same pass runs [GrammarRules] over the same region; findings
 * get a dotted underline and a popup with the finding's message, a one-tap *Fix* through the
 * `Editable` (Ctrl+Z-able) when the rule has a mechanical correction, and a session-scoped mute.
 *
 * **Everything durable here is the host's store, and every one of those calls blocks on Binder I/O**
 * — so each is made from [Dispatchers.IO] and never from Main. That is also why [enabled] cannot be
 * read in the constructor the way og reads its `SharedPreferences`: the field starts at the default
 * (on — absent means on) and [start] corrects it before anything loads.
 *
 * While off — or while the editor is in Preview, which pauses the timer — nothing runs and no spans
 * exist. **The dictionary is only loaded once proofread is actually wanted:** it holds a large
 * in-memory index, and a writer who turned the feature off should not pay for it.
 *
 * **Nothing here logs a word, a line or a message** — counts, lengths and durations only. The words
 * are the writer's own vocabulary and the text is their document.
 */
internal class ProofreadController(
    private val activity: Activity,
    private val editor: ProofreadEditText,
    private val scope: CoroutineScope,
    private val openDictionary: () -> InputStream,
) : ProofreadPeer {

    /** Whether proofread is on. Starts at the default and is corrected by [start]; [setEnabled]
     *  keeps it and the store in step from then on. */
    var enabled: Boolean = true
        private set

    /** True once the dictionary is loaded and checks can actually run. */
    val ready: Boolean get() = engine != null

    /** True once the suggestion index has finished building — until then a popup can offer nothing
     *  and says so instead of claiming the word has no corrections. */
    val suggestionsReady: Boolean get() = engine?.suggestionsReady == true

    /** Everything the writer taps: the feature's sheet, both flag popups, the dictionary manager. */
    private val sheets = ProofreadSheets(activity, scope, this)

    private var engine: SpellEngine? = null
    private var engineRequested = false

    /** Words the user chose to ignore, normalized lowercase. This editor session only. */
    private val ignored = FilterSet()

    /** Muted grammar findings, keyed rule + snippet ([grammarKey]). This editor session only. */
    private val ignoredGrammar = FilterSet()

    /**
     * The durable user dictionary, mirrored in memory so the checking pass never touches the store.
     * Loaded with the engine (see [loadEngine]); adds and removes keep it in step.
     */
    private val userWords = FilterSet()

    /**
     * A set whose every mutation bumps [generation], so a pass launched before the change can never
     * land its now-wrong conclusions (re-installing a flag the user just dismissed). The bump lives
     * in the mutation seam rather than at each call site — a future site cannot forget it.
     * Main-thread only; background passes take [snapshot]s.
     */
    private inner class FilterSet {
        private val set = HashSet<String>()
        val size: Int get() = set.size
        fun snapshot(): HashSet<String> = HashSet(set)
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
     * Bumped whenever what a running pass concluded may no longer hold — on every text change, and
     * on every ignore/dictionary mutation. A background result from an older generation is stale and
     * must not land: it would re-install a flag the user just dismissed.
     */
    private var generation = 0

    /** True while a tap's suggestion lookup is in flight — the second tap of a pile-up is dropped. */
    private var lookupInFlight = false

    init {
        editor.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                // Removing a fence or a backtick changes what is code *below* it, so the outgoing
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

    /**
     * Called once, from the screen. The stored toggle is read first and the dictionary only if it
     * says yes — off must cost nothing at all, not even a load that is thrown away.
     */
    fun start() {
        scope.launch {
            val on = withContext(Dispatchers.IO) { EditorPrefs.proofreadEnabled() }
            enabled = on
            if (on) loadEngine()
        }
    }

    /** Nothing may fire after the screen is gone; the lifecycle scope cancels the rest. */
    fun dispose() {
        handler.removeCallbacks(checkTick)
        if (EditorAutomation.proofread === this) EditorAutomation.proofread = null
    }

    /**
     * Turn the pass on or off, remembered for the device. On clears nothing and checks everything;
     * off cancels whatever is pending and takes every flag off the screen.
     */
    fun setEnabled(on: Boolean) {
        if (enabled == on) return
        enabled = on
        proofreadWrites.launch { EditorPrefs.saveProofreadEnabled(on) }
        Slog.d(TAG) { "proofread ${if (on) "on" else "off"}" }
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
     * Full pass over the current text — the open, page-flip and "Check document" path. A call before
     * the dictionary is ready is not lost: loading completes into exactly this.
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
     * looked up off the main thread — `Verbosity.All` over the whole dictionary is popup-only cost,
     * but it is not free on an e-ink CPU.
     */
    fun onTap(offset: Int) {
        if (!enabled) return
        // A tap that lands inside a selection belongs to the selection workflow (copy, cut, drag
        // handles) — the popup must not cover it.
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
        if (start < 0 || end <= start) {
            lookupInFlight = false
            return
        }
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
            sheets.showSpelling(span, word, suggestions)
        }
    }

    // ── The pass ──────────────────────────────────────────────────────────────

    private fun loadEngine() {
        if (engineRequested) return
        engineRequested = true
        scope.launch {
            val loadStart = SystemClock.elapsedRealtime()
            // Neither a dictionary nor a store that cannot be read may cost the writer more than
            // spell checking — this runs under the editor, and an escaped exception here takes the
            // whole screen down. The user's words ride in with the engine: both must be present
            // before the first pass, or a word added yesterday would flash flagged while the store
            // was on its way. Independent work, so they load side by side.
            val (loaded, words) = try {
                coroutineScope {
                    val engineJob = async { SpellEngine.shared(openDictionary) }
                    val wordsJob = async(Dispatchers.IO) { EditorPrefs.userWords() }
                    engineJob.await() to wordsJob.await()
                }
            } catch (e: CancellationException) {
                throw e // the screen is going away, not the load failing
            } catch (e: Exception) {
                Log.e(TAG, "proofread failed to load and stays quiet", e)
                engineRequested = false // an explicit "Check document" may retry
                return@launch
            }
            userWords.replaceAll(words)
            engine = loaded
            Slog.d(TAG) {
                val ms = SystemClock.elapsedRealtime() - loadStart
                "dictionary ready: ${loaded.wordCount} words + ${words.size} user words in ${ms}ms"
            }
            // The engine checks the moment the word map is up; the SymSpell suggestion index follows
            // in the background, and the tap popup says "suggestions are loading" until it lands.
            if (!loaded.suggestionsReady) proofreadIndexing.launch {
                try {
                    val t = SystemClock.elapsedRealtime()
                    loaded.loadSuggestionIndex()
                    Slog.d(TAG) { "suggestion index ready in ${SystemClock.elapsedRealtime() - t}ms" }
                } catch (e: Exception) {
                    Log.e(TAG, "suggestion index failed to build; popups stay suggestion-less", e)
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
            // getSpans also returns spans merely *touching* the range — a word ending exactly at the
            // region edge belongs to the neighbouring lines and is not this pass's to judge.
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
        Slog.d(TAG) { "checked [${region.start}, ${region.end}): ${flags.size} flagged" }
    }

    /**
     * The grammar twin of [applyFlags], with one more equality axis: a span at unchanged offsets
     * survives only if it still says the same thing — same rule, message and fix — since a pass can
     * re-diagnose the same range.
     */
    private fun applyGrammarFlags(
        region: ProofreadCheck.Region,
        flags: List<GrammarFlag>,
        snapshot: String,
    ) {
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
            // diagnosis unchanged while the text under the span drifts ("the the" → "the The"), and
            // a kept span with a stale snippet would decline every tap forever.
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
        Slog.d(TAG) { "grammar [${region.start}, ${region.end}): ${flags.size} flagged" }
    }

    // ── The sheets ────────────────────────────────────────────────────────────

    /** The feature's sheet — the format bar's Proofread tool. [ProofreadSheets] draws it. */
    fun promptProofread() = sheets.promptProofread()

    /**
     * A grammar flag's popup, when the flag still says what it said. A span whose text no longer
     * matches what was flagged is left silent — the edit that moved it has a re-check already owing.
     */
    private fun showGrammarPopup(span: GrammarFlagSpan) {
        val text = editor.text ?: return
        val start = text.getSpanStart(span)
        val end = text.getSpanEnd(span)
        if (start < 0 || end <= start) return
        if (text.subSequence(start, end).toString() != span.snippet) return
        sheets.showGrammar(span)
    }

    // ── What a tap changes ────────────────────────────────────────────────────
    // Each of these is one row of a [ProofreadSheets] popup, and each is also one of the debug
    // automation's entry points — hence no `private` on the five below.

    /**
     * Make [word] correct from now on, everywhere: into the in-memory set for this session's passes,
     * into the store for every future one. The in-memory half lands **first and synchronously** —
     * the vouch holds for this showing even if the store is unavailable.
     */
    fun addToDictionary(word: String) {
        val w = normalize(word)
        userWords.add(w) // the FilterSet bump keeps any in-flight pass from re-flagging it
        unflag(w)
        proofreadWrites.launch { EditorPrefs.addUserWord(w) }
        Slog.d(TAG) { "added a word to the user dictionary (${userWords.size} in memory)" }
    }

    /** Take [word] back out; whatever the document owes in flags, the fresh pass repays. */
    fun removeFromDictionary(word: String) {
        userWords.remove(word)
        proofreadWrites.launch { EditorPrefs.removeUserWord(word) }
        checkDocument()
        Slog.d(TAG) { "removed a word from the user dictionary" }
    }

    /**
     * Replace a flagged range — spelling or grammar span alike — through the `Editable`, the same
     * route the format bar takes, so the editor's own Ctrl+Z can take it back.
     */
    fun replaceFlag(span: Any, suggestion: String) {
        val text = editor.text ?: return
        val start = text.getSpanStart(span)
        val end = text.getSpanEnd(span)
        if (start < 0 || end <= start) return
        text.removeSpan(span)
        text.replace(start, end, suggestion)
        editor.setSelection((start + suggestion.length).coerceIn(0, text.length))
        Slog.d(TAG) { "replaced a flagged word (${suggestion.length} chars in)" }
    }

    /**
     * Mute this finding for the session — every flag saying the same thing about the same text, not
     * just the tapped one, comes off the screen with it.
     */
    fun muteGrammar(span: GrammarFlagSpan) {
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
        Slog.d(TAG) { "muted a grammar finding for this session (${ignoredGrammar.size} muted)" }
    }

    private fun grammarKey(rule: String, snippet: String): String = "$rule:${snippet.lowercase()}"

    /** Ignore [word] for this session and take its flags — all of them — off the screen. */
    fun ignoreWord(word: String) {
        val w = normalize(word)
        ignored.add(w)
        unflag(w)
        Slog.d(TAG) { "ignoring a word for this session (${ignored.size} ignored)" }
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

    // ── The automation seam (debug walks; release never registers it) ──────────
    // These are [ProofreadPeer]'s: the walk agent's only way to reach a flag, since the Supernote's
    // IME swallows injected text and a scripted tap cannot land on a particular word. Each acts
    // through the same paths a real tap takes, and none of them reports a word.

    override fun proofreadStatus(): String {
        val text = editor.text
        val spelling = text?.getSpans(0, text.length, ProofreadFlagSpan::class.java)?.size ?: 0
        val grammar = text?.getSpans(0, text.length, GrammarFlagSpan::class.java)?.size ?: 0
        return "enabled=$enabled ready=$ready suggestions=$suggestionsReady " +
            "spelling=$spelling grammar=$grammar"
    }

    override fun proofreadCheck() = checkDocument()

    override fun proofreadTap(offset: Int) = onTap(offset)

    /**
     * Apply the first suggestion of the spelling flag at [offset], or the grammar finding's own fix.
     *
     * Answers false — having changed nothing — when there is no flag there, when a grammar finding
     * has no mechanical correction, or when the **suggestion index is still building**: the lookup
     * runs on the caller's thread here, and blocking Main until an index lands would wedge the very
     * screen the walk is driving. A walk polls [proofreadStatus] for `suggestions=true` first.
     */
    override fun proofreadFix(offset: Int): Boolean {
        val text = editor.text ?: return false
        val spelling = text.getSpans(offset, offset, ProofreadFlagSpan::class.java).firstOrNull()
        if (spelling != null) {
            val engine = engine ?: return false
            if (!engine.suggestionsReady) return false
            val start = text.getSpanStart(spelling)
            val end = text.getSpanEnd(spelling)
            if (start < 0 || end <= start) return false
            val suggestion = engine.suggestions(text.subSequence(start, end).toString())
                .firstOrNull() ?: return false
            replaceFlag(spelling, suggestion)
            return true
        }
        val grammar = text.getSpans(offset, offset, GrammarFlagSpan::class.java).firstOrNull()
            ?: return false
        val fix = grammar.replacement ?: return false
        replaceFlag(grammar, fix)
        return true
    }

    /** Ignore the flag at [offset] for this session — the word, or the grammar finding. */
    override fun proofreadIgnore(offset: Int): Boolean {
        val text = editor.text ?: return false
        val spelling = text.getSpans(offset, offset, ProofreadFlagSpan::class.java).firstOrNull()
        if (spelling != null) {
            val start = text.getSpanStart(spelling)
            val end = text.getSpanEnd(spelling)
            if (start < 0 || end <= start) return false
            ignoreWord(text.subSequence(start, end).toString())
            return true
        }
        val grammar = text.getSpans(offset, offset, GrammarFlagSpan::class.java).firstOrNull()
            ?: return false
        muteGrammar(grammar)
        return true
    }

    /** Add the spelling flag's word at [offset] to the durable user dictionary. */
    override fun proofreadAdd(offset: Int): Boolean {
        val text = editor.text ?: return false
        val span = text.getSpans(offset, offset, ProofreadFlagSpan::class.java).firstOrNull()
            ?: return false
        val start = text.getSpanStart(span)
        val end = text.getSpanEnd(span)
        if (start < 0 || end <= start) return false
        addToDictionary(text.subSequence(start, end).toString())
        return true
    }

    companion object {
        private const val TAG = "Proofread"

        /** Typing-idle window before the edited region is re-checked. */
        private const val CHECK_DELAY_MS = 1500L

        /**
         * The bundled dictionary. `.dict` rather than `.gz` although the content **is** gzip: AAPT
         * silently decompresses any `.gz` asset and strips the extension, so the runtime name would
         * not match the source tree. An opaque extension ships byte-identical.
         */
        const val DICTIONARY_ASSET = "proofread/en_82765.dict"

        /**
         * Build the layer and hook it to [editor] — the whole of the screen's wiring, in one call,
         * so that the Activity carries one line of it rather than five.
         *
         * og builds this only once its buffer holds the opening text, so that its watcher never
         * meets a programmatic load. Here the load lands asynchronously, long after `onCreate`, and
         * the watcher does meet it — which is harmless because **every buffer adoption re-checks in
         * full**: the load, and every flip and scope switch after it, run [checkDocument], and that
         * cancels whatever the watcher had just scheduled before doing the whole document properly.
         */
        fun install(
            activity: Activity,
            editor: ProofreadEditText,
            scope: CoroutineScope,
        ): ProofreadController {
            val controller = ProofreadController(activity, editor, scope) {
                activity.assets.open(DICTIONARY_ASSET)
            }
            editor.onWordTap = controller::onTap
            if (BuildConfig.DEBUG) EditorAutomation.proofread = controller
            controller.start()
            return controller
        }
    }
}
