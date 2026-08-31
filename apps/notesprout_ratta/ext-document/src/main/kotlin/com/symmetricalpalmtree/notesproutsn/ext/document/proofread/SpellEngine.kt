package com.symmetricalpalmtree.notesproutsn.ext.document.proofread

import com.darkrockstudios.symspellkt.common.DictionaryItem
import com.darkrockstudios.symspellkt.common.SpellCheckSettings
import com.darkrockstudios.symspellkt.common.Verbosity
import com.darkrockstudios.symspellkt.exception.SpellCheckException
import com.darkrockstudios.symspellkt.impl.SymSpell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * The proofread spell checker: the English frequency dictionary bundled in this extension's APK
 * (`assets/proofread/en_82765.dict`, attribution in the `NOTICE.txt` beside it) with SymSpellKt
 * behind it for correction candidates. The `.dict` file holds gzipped `term frequency` lines; the
 * extension is opaque on purpose, because AAPT decompresses a `.gz` asset at build time and strips
 * the extension from the APK.
 *
 * Pure Kotlin/JVM by design: the dictionary arrives as an [InputStream], so JVM tests read the
 * exact asset the extension ships (the test source set mounts `src/main/assets` as resources) and
 * the editor hands in `assets.open(...)`. Nothing here touches Android — and nothing here logs;
 * document text never reaches a log on either side of the extension seam.
 *
 * **Loading is two-stage, because checking and suggesting have wildly different costs.** [isKnown]
 * needs only the word→frequency map — one pass over the file, fast everywhere. The SymSpell delete
 * index behind [suggestions] is ~30 derived entries per word, and building it measured **tens of
 * seconds on a Supernote's CPU** (under a second on a desktop JVM — the gap is the device). Flags
 * must not wait on that: [load] builds the map and the engine can check immediately;
 * [loadSuggestionIndex] feeds *the same map* into SymSpell afterwards — the asset is never read
 * twice, so the index can never diverge from the words — and until it lands [suggestions] honestly
 * returns nothing while [suggestionsReady] is false, which the popup says out loud instead of
 * pretending there are no candidates.
 *
 * The map keeps frequencies, not just membership: suggestion ranking is frequency-ordered, so a
 * `Set` of known words would silently reorder every candidate list.
 *
 * The dictionary indexes lowercase terms, so knowledge is case-insensitive and casing is this
 * class's job: [suggestions] re-shapes each candidate to the shape of the word being corrected
 * ("Teh" → "The"). [shouldCheck] is the conservative gate — it refuses to judge anything the
 * English dictionary cannot honestly judge (digits, acronyms, mixed case, non-ASCII letters);
 * silence over noise, per the proofread design.
 */
class SpellEngine private constructor(private val frequencies: HashMap<String, Double>) {

    /** Number of correctly spelled words known — exposed for tests and the load-time log. */
    val wordCount: Int get() = frequencies.size

    @Volatile
    private var checker: SymSpell? = null
    private val indexMutex = Mutex()

    /** True once [loadSuggestionIndex] has finished and [suggestions] can actually suggest. */
    val suggestionsReady: Boolean get() = checker != null

    /**
     * Whether [word] is spelled correctly. Case-insensitive; typographic apostrophes count as
     * plain ones; a possessive ("gardener's") is known when its stem is.
     */
    fun isKnown(word: String): Boolean {
        val w = normalizeWord(word)
        if (frequencies.containsKey(w)) return true
        val stem = when {
            w.endsWith("'s") -> w.dropLast(2)
            w.endsWith("'") -> w.dropLast(1)
            else -> return false
        }
        return stem.isNotEmpty() && frequencies.containsKey(stem)
    }

    /**
     * Correction candidates for [word], best first, cased like the input. Empty when nothing within
     * edit distance 2 is found — or when the suggestion index is still building ([suggestionsReady]
     * tells the two apart). Called on tap (the popup), not during the checking pass, so it affords
     * [Verbosity.All] — the full distance-ordered list, not just the nearest tier.
     */
    fun suggestions(word: String, limit: Int = MAX_SUGGESTIONS): List<String> {
        val checker = checker ?: return emptyList()
        val w = normalizeWord(word)
        val items = try {
            checker.lookup(w, Verbosity.All)
        } catch (e: SpellCheckException) {
            return emptyList()
        }
        return items.asSequence()
            .map { it.term.trim() }
            .filter { it.isNotEmpty() && it != w }
            .distinct()
            .take(limit)
            .map { matchCase(word, it) }
            .toList()
    }

    /**
     * Builds the SymSpell suggestion index from the words [load] already parsed — each word's
     * frequency carries over, so ranking matches a file-fed SymSpell exactly (this is what
     * `loadUniGramLine` does per line, minus the parsing). Safe to call twice (the second call
     * finds it built and returns); safe to retry after a failure.
     */
    suspend fun loadSuggestionIndex(): Unit =
        withContext(Dispatchers.Default) {
            indexMutex.withLock {
                if (checker != null) return@withLock
                val symSpell = SymSpell(spellCheckSettings = SpellCheckSettings())
                for ((term, frequency) in frequencies) {
                    symSpell.dictionary.addItem(DictionaryItem(term, frequency, -1.0))
                }
                checker = symSpell
            }
        }

    private fun matchCase(original: String, suggestion: String): String =
        if (original.first().isUpperCase()) {
            suggestion.replaceFirstChar { it.uppercaseChar() }
        } else {
            suggestion
        }

    companion object {
        const val MAX_SUGGESTIONS = 5

        @Volatile
        private var sharedInstance: SpellEngine? = null
        private val loadMutex = Mutex()

        /**
         * Whether [word] is something the English dictionary can honestly judge. Refuses single
         * letters, anything carrying a digit or a non-ASCII letter ("café" is not misspelled
         * English, it is not English), and any case shape other than all-lowercase or Titlecase —
         * which spares acronyms ("EPD") and branded casing ("iPad") without needing a list of them.
         */
        fun shouldCheck(word: String): Boolean {
            if (word.length < 2) return false
            val w = word.replace('’', '\'')
            if (!w.all { it == '\'' || it in 'a'..'z' || it in 'A'..'Z' }) return false
            return w.drop(1).none { it in 'A'..'Z' }
        }

        /** The process-wide engine, loaded on first call; [openDictionary] is not invoked again. */
        suspend fun shared(openDictionary: () -> InputStream): SpellEngine {
            sharedInstance?.let { return it }
            return loadMutex.withLock {
                sharedInstance ?: load(openDictionary()).also { sharedInstance = it }
            }
        }

        /**
         * Builds a checking-ready engine from a gzipped `term frequency`-per-line dictionary
         * stream — the fast stage; see the class doc. [loadSuggestionIndex] completes it.
         */
        suspend fun load(gzippedDictionary: InputStream): SpellEngine =
            withContext(Dispatchers.Default) {
                val frequencies = HashMap<String, Double>(110_000)
                GZIPInputStream(gzippedDictionary).bufferedReader(Charsets.UTF_8).use { reader ->
                    reader.forEachLine { raw ->
                        // A UTF-8 BOM on the first line would glue itself to the first term.
                        val line = raw.removePrefix("﻿")
                        val cut = line.indexOf(' ')
                        if (cut > 0) {
                            val frequency = line.substring(cut + 1).trim().toDoubleOrNull()
                            if (frequency != null) frequencies[line.substring(0, cut)] = frequency
                        }
                    }
                }
                SpellEngine(frequencies)
            }

        /**
         * The engine's normal form for a word: lowercase, typographic apostrophe folded to plain.
         * Also the storage form of the user dictionary and every ignore set — membership anywhere
         * is comparison in this form.
         */
        fun normalizeWord(word: String): String =
            word.replace('’', '\'').lowercase()
    }
}
