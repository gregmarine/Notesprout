package com.symmetricalpalmtree.notesproutsn.data.template

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TemplateSearchTest {

    // ── likePattern ──────────────────────────────────────────────────────────

    @Test
    fun `wraps the query for a substring match`() {
        assertEquals("%grid%", TemplateSearch.likePattern("grid"))
    }

    @Test
    fun `trims before wrapping`() {
        assertEquals("%grid%", TemplateSearch.likePattern("  grid  "))
    }

    /**
     * `_` is in the family name charset (`NameRules.CHARSET`), so a template really can be called
     * `my_grid` and a user really can search for `_`. Unescaped it is LIKE's any-single-character
     * and the shelf silently fills with every template in the library.
     */
    @Test
    fun `escapes the underscore wildcard`() {
        assertEquals("%my\\_grid%", TemplateSearch.likePattern("my_grid"))
    }

    @Test
    fun `escapes the percent wildcard`() {
        assertEquals("%50\\%%", TemplateSearch.likePattern("50%"))
    }

    @Test
    fun `escapes the escape character itself`() {
        assertEquals("%a\\\\b%", TemplateSearch.likePattern("a\\b"))
    }

    @Test
    fun `an empty query still produces a well formed pattern`() {
        assertEquals("%%", TemplateSearch.likePattern("   "))
    }

    // ── matchesLabel — the same rule SQLite's LIKE applies ───────────────────

    @Test
    fun `matches a substring anywhere in the label`() {
        assertTrue(TemplateSearch.matchesLabel("Dotted", "ott"))
    }

    /** The whole reason this function exists: LIKE is ASCII case-insensitive, so a sentinel matched
     *  case-sensitively would make "Grid" findable and "grid" not — for the built-in only. */
    @Test
    fun `matches regardless of case, both ways`() {
        assertTrue(TemplateSearch.matchesLabel("Grid", "grid"))
        assertTrue(TemplateSearch.matchesLabel("grid", "GRID"))
    }

    @Test
    fun `trims the query the way likePattern does`() {
        assertTrue(TemplateSearch.matchesLabel("Lined", "  lin "))
    }

    @Test
    fun `an empty query matches nothing`() {
        assertFalse(TemplateSearch.matchesLabel("Lined", ""))
        assertFalse(TemplateSearch.matchesLabel("Lined", "   "))
    }

    @Test
    fun `a non-matching query matches nothing`() {
        assertFalse(TemplateSearch.matchesLabel("Lined", "grid"))
    }

    // ── isRunnable ───────────────────────────────────────────────────────────

    @Test
    fun `blank queries are not runnable`() {
        assertFalse(TemplateSearch.isRunnable(""))
        assertFalse(TemplateSearch.isRunnable("   "))
        assertFalse(TemplateSearch.isRunnable("\t\n"))
    }

    @Test
    fun `anything with a character is runnable`() {
        assertTrue(TemplateSearch.isRunnable("a"))
        assertTrue(TemplateSearch.isRunnable("  a  "))
    }
}
