package com.notesprout.android.core.proofread

import com.darkrockstudios.symspellkt.common.SpellCheckSettings
import com.darkrockstudios.symspellkt.common.Verbosity
import com.darkrockstudios.symspellkt.exception.SpellCheckException
import com.darkrockstudios.symspellkt.impl.SymSpell
import com.darkrockstudios.symspellkt.impl.loadUniGramLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * The proofread spell checker — SymSpellKt wrapped around the bundled English frequency
 * dictionary (`assets/proofread/en_82765.dict`, attribution in `NOTICE.txt` beside it). The
 * `.dict` file is gzipped `term frequency` lines; the extension is opaque on purpose, because
 * AAPT decompresses a `.gz` asset at build time and strips the extension from the APK.
 *
 * Pure Kotlin/JVM by design: the dictionary arrives as an [InputStream], so JVM tests load the
 * exact asset the app ships (the test source set mounts `src/main/assets` as resources) and the
 * editor hands in `context.assets.open(...)`. Nothing here touches Android.
 *
 * The dictionary indexes lowercase terms, so knowledge is case-insensitive and casing is this
 * class's job: [suggestions] re-shapes each candidate to the shape of the word being corrected
 * ("Teh" → "The"). [shouldCheck] is the conservative gate — it refuses to judge anything the
 * English dictionary cannot honestly judge (digits, acronyms, mixed case, non-ASCII letters);
 * silence over noise, per the proofread design.
 *
 * Loading builds the SymSpell delete index for ~82k words — seconds, not millis — so it happens
 * once, off the main thread, via [shared]. Word checks after that are microseconds.
 */
class SpellEngine private constructor(private val checker: SymSpell) {

    /** Number of correctly spelled words indexed — exposed for tests and the load-time log. */
    val wordCount: Int get() = checker.dictionary.wordCount

    /**
     * Whether [word] is spelled correctly. Case-insensitive; typographic apostrophes count as
     * plain ones; a possessive ("gardener's") is known when its stem is.
     */
    fun isKnown(word: String): Boolean {
        val w = normalizeWord(word)
        if (frequency(w) != null) return true
        val stem = when {
            w.endsWith("'s") -> w.dropLast(2)
            w.endsWith("'") -> w.dropLast(1)
            else -> return false
        }
        return stem.isNotEmpty() && frequency(stem) != null
    }

    /**
     * Correction candidates for [word], best first, cased like the input. Empty when nothing
     * within edit distance 2 is found. Called on tap (popup), not during the checking pass, so
     * it affords [Verbosity.All] — the full distance-ordered list, not just the nearest tier.
     */
    fun suggestions(word: String, limit: Int = MAX_SUGGESTIONS): List<String> {
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

    private fun frequency(term: String): Double? = try {
        checker.dictionary.getItemFrequency(term)
    } catch (e: SpellCheckException) {
        null
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
         * letters, anything with a digit or a non-ASCII letter ("café" is not misspelled English,
         * it is not English), and any case shape other than all-lowercase or Titlecase — which
         * spares acronyms ("EPD") and branded casing ("iPad") without needing a list of them.
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

        /** Builds an engine from a gzipped `term frequency`-per-line dictionary stream. */
        suspend fun load(gzippedDictionary: InputStream): SpellEngine =
            withContext(Dispatchers.Default) {
                val checker = SymSpell(spellCheckSettings = SpellCheckSettings())
                GZIPInputStream(gzippedDictionary).bufferedReader(Charsets.UTF_8).use { reader ->
                    reader.forEachLine { line ->
                        checker.dictionary.loadUniGramLine(line.removePrefix("﻿"))
                    }
                }
                SpellEngine(checker)
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
