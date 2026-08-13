package com.notesprout.android.core.proofread

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins every grammar-essentials rule — and, just as deliberately, every guard. Each rule's
 * false-positive cases are legitimate prose the unguarded rule would flag; a regression here means
 * noise on real documents, which the feature's charter (silence over noise) forbids.
 */
class GrammarRulesTest {

    private fun check(text: String): List<GrammarFlag> =
        GrammarRules.check(text, ProofreadCheck.Region(0, text.length))

    private fun rules(text: String): List<String> = check(text).map { it.rule }

    private fun only(text: String, rule: String): GrammarFlag {
        val flags = check(text).filter { it.rule == rule }
        assertEquals("expected exactly one $rule flag in: $text", 1, flags.size)
        return flags[0]
    }

    private fun none(text: String, rule: String) {
        val flags = check(text).filter { it.rule == rule }
        assertTrue("expected no $rule flags in: $text, got $flags", flags.isEmpty())
    }

    // ── Repeated word ─────────────────────────────────────────────────────────

    @Test
    fun repeated_word_is_flagged_with_a_single_word_fix() {
        val flag = only("I saw the the garden", GrammarRules.RULE_REPEATED)
        assertEquals("the the", "I saw the the garden".substring(flag.start, flag.end))
        assertEquals("the", flag.replacement)
    }

    @Test
    fun repeated_word_keeps_the_first_words_case() {
        val flag = only("The the garden grows", GrammarRules.RULE_REPEATED)
        assertEquals("The", flag.replacement)
    }

    @Test
    fun repeated_word_matches_case_insensitively() {
        only("watch The the garden", GrammarRules.RULE_REPEATED)
    }

    @Test
    fun grammatical_doubles_are_not_flagged() {
        none("the work he had had done", GrammarRules.RULE_REPEATED)
        none("I know that that is true", GrammarRules.RULE_REPEATED)
        none("it was very very good", GrammarRules.RULE_REPEATED)
        none("no no, wait", GrammarRules.RULE_REPEATED)
    }

    @Test
    fun capitalized_pairs_are_proper_nouns_not_typos() {
        none("we visited Walla Walla in spring", GrammarRules.RULE_REPEATED)
        none("listening to Duran Duran", GrammarRules.RULE_REPEATED)
    }

    @Test
    fun repeats_across_a_line_break_are_not_flagged() {
        none("end of thought\nthought resumes here", GrammarRules.RULE_REPEATED)
    }

    @Test
    fun repeated_numbers_are_data_not_typos() {
        none("scores were 2 2 and 3", GrammarRules.RULE_REPEATED)
    }

    @Test
    fun repeats_inside_code_are_invisible() {
        none("run `go go` now", GrammarRules.RULE_REPEATED)
    }

    // ── Sentence capitalization ───────────────────────────────────────────────

    @Test
    fun lowercase_sentence_start_is_flagged_with_a_capitalized_fix() {
        val flag = only("It rained. the garden liked it", GrammarRules.RULE_CAPITALIZE)
        assertEquals("the", "It rained. the garden liked it".substring(flag.start, flag.end))
        assertEquals("The", flag.replacement)
    }

    @Test
    fun question_and_exclamation_count_as_sentence_ends() {
        only("Really? yes indeed", GrammarRules.RULE_CAPITALIZE)
        only("Grow! water it daily", GrammarRules.RULE_CAPITALIZE)
    }

    @Test
    fun closing_punctuation_may_sit_between_terminator_and_space() {
        only("He said “stop.” then he left", GrammarRules.RULE_CAPITALIZE)
        only("(It worked.) so we kept it", GrammarRules.RULE_CAPITALIZE)
        only("**Bold end.** next sentence", GrammarRules.RULE_CAPITALIZE)
    }

    @Test
    fun paragraph_and_document_starts_are_not_judged() {
        none("lowercase fragment note", GrammarRules.RULE_CAPITALIZE)
        none("First line.\nsecond line fragment", GrammarRules.RULE_CAPITALIZE)
    }

    @Test
    fun abbreviations_do_not_end_sentences() {
        none("see Dr. smith about it", GrammarRules.RULE_CAPITALIZE)
        none("apples, pears, etc. were plentiful", GrammarRules.RULE_CAPITALIZE)
        none("compare X vs. y here", GrammarRules.RULE_CAPITALIZE)
    }

    @Test
    fun single_letters_are_initials_not_sentence_ends() {
        none("e.g. lowercase is fine", GrammarRules.RULE_CAPITALIZE)
        none("i.e. this stays quiet", GrammarRules.RULE_CAPITALIZE)
        none("John Q. public said", GrammarRules.RULE_CAPITALIZE)
    }

    @Test
    fun numbers_before_the_period_are_not_sentence_ends() {
        none("version 3. beta follows", GrammarRules.RULE_CAPITALIZE)
        none("pi is 3.14159 roughly", GrammarRules.RULE_CAPITALIZE)
    }

    @Test
    fun ellipsis_trails_off_without_a_capital() {
        none("and then... nothing happened", GrammarRules.RULE_CAPITALIZE)
    }

    @Test
    fun file_names_do_not_trip_the_rule() {
        none("see readme.md for details", GrammarRules.RULE_CAPITALIZE)
    }

    @Test
    fun lone_i_after_a_period_belongs_to_the_lone_i_rule() {
        val flags = check("It ended. i went home")
        assertEquals(listOf(GrammarRules.RULE_LONE_I), flags.map { it.rule })
    }

    // ── Lone lowercase i ──────────────────────────────────────────────────────

    @Test
    fun lone_lowercase_i_is_flagged() {
        val flag = only("yesterday i planted seeds", GrammarRules.RULE_LONE_I)
        assertEquals("I", flag.replacement)
    }

    @Test
    fun lowercase_i_contractions_are_flagged() {
        assertEquals("I'm", only("i'm ready", GrammarRules.RULE_LONE_I).replacement)
        assertEquals("I’ll", only("i’ll water it", GrammarRules.RULE_LONE_I).replacement)
        assertEquals("I've", only("i've seen it", GrammarRules.RULE_LONE_I).replacement)
        assertEquals("I'd", only("i'd like that", GrammarRules.RULE_LONE_I).replacement)
    }

    @Test
    fun notation_i_is_not_the_pronoun() {
        none("the i.e. case stays quiet", GrammarRules.RULE_LONE_I)
        none("an i-beam holds the roof", GrammarRules.RULE_LONE_I)
        none("run `i` in the loop", GrammarRules.RULE_LONE_I)
    }

    @Test
    fun capital_I_is_already_right() {
        none("I planted seeds", GrammarRules.RULE_LONE_I)
        none("I'm ready", GrammarRules.RULE_LONE_I)
    }

    @Test
    fun other_apostrophe_words_starting_with_i_stay_quiet() {
        none("the it's trap", GrammarRules.RULE_LONE_I)
    }

    // ── a / an ────────────────────────────────────────────────────────────────

    @Test
    fun a_before_a_vowel_sound_is_flagged() {
        assertEquals("an", only("a apple a day", GrammarRules.RULE_A_AN).replacement)
        assertEquals("an", only("what a idea", GrammarRules.RULE_A_AN).replacement)
        assertEquals("An", only("A orange fell", GrammarRules.RULE_A_AN).replacement)
    }

    @Test
    fun the_flag_covers_only_the_article() {
        val text = "a apple a day"
        val flag = only(text, GrammarRules.RULE_A_AN)
        assertEquals("a", text.substring(flag.start, flag.end))
    }

    @Test
    fun an_before_a_consonant_sound_is_flagged() {
        assertEquals("a", only("an cat appeared", GrammarRules.RULE_A_AN).replacement)
        assertEquals("A", only("An garden grows", GrammarRules.RULE_A_AN).replacement)
    }

    @Test
    fun silent_h_takes_an() {
        assertEquals("an", only("a hour passed", GrammarRules.RULE_A_AN).replacement)
        assertEquals("an", only("a honest answer", GrammarRules.RULE_A_AN).replacement)
        none("an hour passed", GrammarRules.RULE_A_AN)
        none("an heir appeared", GrammarRules.RULE_A_AN)
        none("a hotel nearby", GrammarRules.RULE_A_AN)
    }

    @Test
    fun u_words_are_never_judged() {
        none("a university nearby", GrammarRules.RULE_A_AN)
        none("a user signed in", GrammarRules.RULE_A_AN)
        none("a unicorn appeared", GrammarRules.RULE_A_AN)
        none("an umbrella helps", GrammarRules.RULE_A_AN)
        none("an uninteresting fact", GrammarRules.RULE_A_AN)
    }

    @Test
    fun consonant_sounding_vowel_starts_take_a() {
        none("a one-time offer", GrammarRules.RULE_A_AN)
        none("a once-only pass", GrammarRules.RULE_A_AN)
        none("a euro coin", GrammarRules.RULE_A_AN)
        none("a ewe grazed", GrammarRules.RULE_A_AN)
    }

    @Test
    fun acronyms_and_digits_are_never_judged() {
        none("a FBI report", GrammarRules.RULE_A_AN)
        none("an FBI report", GrammarRules.RULE_A_AN)
        none("a 8-year plan", GrammarRules.RULE_A_AN)
    }

    @Test
    fun article_pairs_across_lines_are_not_judged() {
        none("I picked a\napple stem", GrammarRules.RULE_A_AN)
    }

    @Test
    fun a_a_belongs_to_the_repeated_word_rule() {
        val flags = check("saw a a bird")
        assertEquals(listOf(GrammarRules.RULE_REPEATED), flags.map { it.rule })
    }

    // ── Unpaired quotes and brackets ──────────────────────────────────────────

    @Test
    fun a_lone_opening_bracket_is_flagged_without_a_fix() {
        val text = "the result (see below is wrong"
        val flag = only(text, GrammarRules.RULE_UNPAIRED)
        assertEquals("(", text.substring(flag.start, flag.end))
        assertNull(flag.replacement)
    }

    @Test
    fun a_lone_closing_bracket_is_flagged() {
        val text = "wrong result] here"
        val flag = only(text, GrammarRules.RULE_UNPAIRED)
        assertEquals("]", text.substring(flag.start, flag.end))
    }

    @Test
    fun balanced_pairs_stay_quiet() {
        none("the result (see below) is right", GrammarRules.RULE_UNPAIRED)
        none("nested (like [this]) works", GrammarRules.RULE_UNPAIRED)
        none("a [note] and (aside) coexist", GrammarRules.RULE_UNPAIRED)
    }

    @Test
    fun an_odd_straight_quote_is_flagged() {
        val text = "she said \"hello and left"
        val flag = only(text, GrammarRules.RULE_UNPAIRED)
        assertEquals("\"", text.substring(flag.start, flag.end))
        none("she said \"hello\" and left", GrammarRules.RULE_UNPAIRED)
    }

    @Test
    fun an_unbalanced_smart_quote_is_flagged() {
        only("she said “hello and left", GrammarRules.RULE_UNPAIRED)
        none("she said “hello” and left", GrammarRules.RULE_UNPAIRED)
    }

    @Test
    fun inch_marks_after_digits_are_not_quotes() {
        none("the board is 24\" long", GrammarRules.RULE_UNPAIRED)
    }

    @Test
    fun emoticons_keep_their_smiles() {
        none("sounds good :)", GrammarRules.RULE_UNPAIRED)
        none("fine by me ;)", GrammarRules.RULE_UNPAIRED)
        none("classic :-)", GrammarRules.RULE_UNPAIRED)
    }

    @Test
    fun enumeration_markers_are_not_brackets() {
        none("1) first point", GrammarRules.RULE_UNPAIRED)
        none("a) first item", GrammarRules.RULE_UNPAIRED)
        none("first 2) second mid-line", GrammarRules.RULE_UNPAIRED)
    }

    @Test
    fun a_closer_after_a_long_word_is_still_flagged() {
        only("wrong result) here", GrammarRules.RULE_UNPAIRED)
    }

    @Test
    fun pairs_split_across_lines_are_judged_per_line() {
        assertEquals(2, check("open (here\nclose) there").size)
    }

    @Test
    fun markdown_constructs_stay_quiet() {
        none("- [ ] water the garden", GrammarRules.RULE_UNPAIRED)
        none("a [link](https://example.com/a(b) here", GrammarRules.RULE_UNPAIRED)
        none("code `if (x` stays quiet", GrammarRules.RULE_UNPAIRED)
    }

    @Test
    fun mismatched_nesting_flags_the_closer_and_the_opener() {
        val flags = check("wrong (nesting] here").filter { it.rule == GrammarRules.RULE_UNPAIRED }
        assertEquals(2, flags.size)
    }

    // ── Region filtering ──────────────────────────────────────────────────────

    @Test
    fun only_flags_intersecting_the_region_are_returned() {
        val text = "the the birds\nfly fly away"
        val secondLine = ProofreadCheck.Region(14, text.length)
        val flags = GrammarRules.check(text, secondLine)
        assertEquals(1, flags.size)
        assertEquals("fly fly", text.substring(flags[0].start, flags[0].end))
    }

    @Test
    fun flags_arrive_in_document_order() {
        val starts = check("a apple and the the rest (unclosed").map { it.start }
        assertEquals(starts.sorted(), starts)
    }

    @Test
    fun empty_text_yields_nothing() {
        assertTrue(check("").isEmpty())
    }

    @Test
    fun clean_prose_yields_nothing() {
        val text = "The garden grew well this spring. Every seed came up, and I " +
            "watered them (twice a day) until the \"big sprout\" arrived."
        assertTrue(check(text).isEmpty())
    }
}
