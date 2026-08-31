package com.symmetricalpalmtree.notesproutsn.export

import com.symmetricalpalmtree.notesproutsn.extension.ExporterContract
import com.symmetricalpalmtree.notesproutsn.extension.ExporterInfo
import com.symmetricalpalmtree.notesproutsn.extension.OptionDescriptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The options seam's host half. The parcelables are constructed directly — no `Parcel` is touched,
 * so no device is needed (the `:extension-api` tests do the same).
 */
class ExportOptionsTest {

    private fun choice(
        id: String,
        choiceIds: List<String>,
        default: String,
    ) = OptionDescriptor(
        id, "Label", ExporterContract.KIND_SINGLE_CHOICE,
        choiceIds, choiceIds.map { "Label of $it" }, default,
    )

    private fun toggle(id: String, default: String) =
        OptionDescriptor(id, "Label", ExporterContract.KIND_TOGGLE, emptyList(), emptyList(), default)

    private fun passphrase(id: String) =
        OptionDescriptor(id, "Label", ExporterContract.KIND_PASSPHRASE, emptyList(), emptyList(), "")

    private fun info(vararg options: OptionDescriptor) =
        ExporterInfo("Notesprout notebook", "soil", "application/octet-stream", options.toList())

    private fun pagesInfo(vararg options: OptionDescriptor) =
        ExporterInfo(
            "PDF document", "pdf", "application/pdf", options.toList(),
            ExporterContract.SOURCE_PAGES,
        )

    /** E1's shipped descriptor: the keying option, Keep-only. */
    private val e1 = info(choice(ExporterContract.OPTION_KEYING, listOf(ExporterContract.KEYING_KEEP), ExporterContract.KEYING_KEEP))

    @Test
    fun everyKindTheHostCanDrawIsRenderable() {
        assertTrue(ExportOptions.isRenderable(e1))
        assertTrue(ExportOptions.isRenderable(info()))
        assertTrue(ExportOptions.isRenderable(info(toggle("verify", "1"))))
    }

    @Test
    fun anUnknownKeyingChoiceIsNotRenderable() {
        // Keying is host-EXECUTED, not just host-drawn: a choice id the host has no transform for
        // would otherwise surface only at export time as ExportKeying.plan's rejection — which the
        // flow explains as the passphrase-lost state (arc-15 review). Dropped at discovery instead.
        val unknown = info(
            choice(
                ExporterContract.OPTION_KEYING,
                listOf(ExporterContract.KEYING_KEEP, "shamir"),
                ExporterContract.KEYING_KEEP,
            ),
        )
        assertFalse(ExportOptions.isRenderable(unknown))
        // The full trio and any subset of it stay renderable.
        assertTrue(ExportOptions.isRenderable(trio))
        assertTrue(
            ExportOptions.isRenderable(
                info(
                    choice(
                        ExporterContract.OPTION_KEYING,
                        listOf(ExporterContract.KEYING_KEEP, ExporterContract.KEYING_PLAIN),
                        ExporterContract.KEYING_KEEP,
                    ),
                ),
            ),
        )
        // The reserved id declared as another kind is not the reserved option — an ordinary toggle
        // named "keying" renders like any toggle (and keying() already answers null for it).
        assertTrue(ExportOptions.isRenderable(info(toggle(ExporterContract.OPTION_KEYING, "0"))))
    }

    @Test
    fun aFreeStandingPassphraseDescriptorIsNotRenderable() {
        // The one host-executed passphrase step is the reserved keying's rekey choice, whose
        // fields the host shows itself — a passphrase *kind* still has no meaning to execute.
        assertFalse(ExportOptions.isRenderable(info(passphrase("pw"))))
        // One unrenderable option takes the whole exporter with it — the host drops it wholesale.
        assertFalse(ExportOptions.isRenderable(info(toggle("verify", "0"), passphrase("pw"))))
    }

    @Test
    fun emptyChosenMapGivesTheDeclaredDefaults() {
        val d = ExportOptions.specValues(e1, emptyMap())
        assertEquals(mapOf(ExporterContract.OPTION_KEYING to ExporterContract.KEYING_KEEP), d)
        assertEquals(
            mapOf("a" to "b", "verify" to "1"),
            ExportOptions.specValues(info(choice("a", listOf("b", "c"), "b"), toggle("verify", "1")), emptyMap()),
        )
    }

    @Test
    fun chosenValuesCrossWhenTheyAreDeclared() {
        val i = info(choice("keying", listOf("keep", "rekey", "plain"), "keep"), toggle("verify", "0"))
        val out = ExportOptions.specValues(i, mapOf("keying" to "plain", "verify" to "1"))
        assertEquals(mapOf("keying" to "plain", "verify" to "1"), out)
    }

    @Test
    fun anUndeclaredValueFallsBackToTheDefault() {
        val i = info(choice("keying", listOf("keep", "plain"), "keep"), toggle("verify", "1"))
        // A stale pick left over from another exporter's descriptor, and a toggle that is neither.
        val out = ExportOptions.specValues(i, mapOf("keying" to "rekey", "verify" to "yes"))
        assertEquals(mapOf("keying" to "keep", "verify" to "1"), out)
    }

    @Test
    fun anEntryTheExporterNeverDeclaredIsDropped() {
        val out = ExportOptions.specValues(e1, mapOf("keying" to "keep", "smuggled" to "value"))
        assertEquals(setOf("keying"), out.keys)
    }

    @Test
    fun aPassphraseOptionHasNoEntryAtAll() {
        // Not blank, not empty: absent. The host consumed the secret; nothing about it crosses.
        val i = info(choice("keying", listOf("keep"), "keep"), passphrase("pw"))
        val out = ExportOptions.specValues(i, mapOf("keying" to "keep", "pw" to "hunter2"))
        assertEquals(setOf("keying"), out.keys)
        assertFalse(out.containsKey("pw"))
    }

    @Test
    fun theSpecMapIsInDeclarationOrder() {
        val i = info(choice("z", listOf("1"), "1"), toggle("a", "0"), choice("m", listOf("x", "y"), "y"))
        assertEquals(listOf("z", "a", "m"), ExportOptions.specValues(i, emptyMap()).keys.toList())
    }

    @Test
    fun oneChoiceIsNotAChoice() {
        assertTrue(ExportOptions.isFixed(choice("keying", listOf("keep"), "keep")))
        assertFalse(ExportOptions.isFixed(choice("keying", listOf("keep", "plain"), "keep")))
        assertFalse(ExportOptions.isFixed(toggle("verify", "1")))
    }

    @Test
    fun choiceLabelNamesTheChosenValueAndNeverRendersBlank() {
        val d = choice("keying", listOf("keep", "plain"), "keep")
        assertEquals("Label of keep", ExportOptions.choiceLabel(d, "keep"))
        assertEquals("Label of plain", ExportOptions.choiceLabel(d, "plain"))
        assertEquals("mystery", ExportOptions.choiceLabel(d, "mystery"))
    }

    // ── The reserved keying option (E2) ──────────────────────────────────────

    /** E2's shipped descriptor: the keying option, the full trio. */
    private val trio = info(
        choice(
            ExporterContract.OPTION_KEYING,
            listOf(ExporterContract.KEYING_KEEP, ExporterContract.KEYING_REKEY, ExporterContract.KEYING_PLAIN),
            ExporterContract.KEYING_KEEP,
        ),
    )

    @Test
    fun keyingAnswersTheArmedValueAndDefaultsLikeTheSpec() {
        assertEquals(ExporterContract.KEYING_KEEP, ExportOptions.keying(trio, emptyMap()))
        assertEquals(
            ExporterContract.KEYING_REKEY,
            ExportOptions.keying(trio, mapOf(ExporterContract.OPTION_KEYING to ExporterContract.KEYING_REKEY)),
        )
        // A stale value from another descriptor falls back to the default, exactly as specValues would.
        assertEquals(
            ExporterContract.KEYING_KEEP,
            ExportOptions.keying(e1, mapOf(ExporterContract.OPTION_KEYING to ExporterContract.KEYING_PLAIN)),
        )
    }

    @Test
    fun keyingIsNullWhenTheExporterNeverDeclaredIt() {
        assertNull(ExportOptions.keying(info(toggle("verify", "1")), emptyMap()))
        // The reserved id declared as the wrong kind is not the reserved option.
        assertNull(ExportOptions.keying(info(toggle(ExporterContract.OPTION_KEYING, "0")), emptyMap()))
        assertFalse(ExportOptions.needsPassphrase(info(), emptyMap()))
        assertFalse(ExportOptions.showsPlainWarning(info(), emptyMap()))
    }

    @Test
    fun rekeyArmsThePassphraseFieldsAndPlainArmsTheWarning() {
        assertFalse(ExportOptions.needsPassphrase(trio, emptyMap()))
        assertFalse(ExportOptions.showsPlainWarning(trio, emptyMap()))
        val rekey = mapOf(ExporterContract.OPTION_KEYING to ExporterContract.KEYING_REKEY)
        assertTrue(ExportOptions.needsPassphrase(trio, rekey))
        assertFalse(ExportOptions.showsPlainWarning(trio, rekey))
        val plain = mapOf(ExporterContract.OPTION_KEYING to ExporterContract.KEYING_PLAIN)
        assertFalse(ExportOptions.needsPassphrase(trio, plain))
        assertTrue(ExportOptions.showsPlainWarning(trio, plain))
    }

    // ── The reserved arc-18 toggles (D2) ─────────────────────────────────────

    /** D2's shipped PDF descriptor: the pair, both toggles, no keying at all — on SOURCE_PAGES,
     *  where the template toggle is allowed to live (the D3 source-kind gate). */
    private val pdf = pagesInfo(
        toggle(ExporterContract.OPTION_PAGE_TEMPLATE, "1"),
        toggle(ExporterContract.OPTION_PROTECT, "0"),
    )

    @Test
    fun protectArmsTheFieldsOnlyWhenItIsDeclaredAndOn() {
        assertFalse(ExportOptions.wantsExportSecret(pdf, emptyMap()))
        assertTrue(ExportOptions.wantsExportSecret(pdf, mapOf(ExporterContract.OPTION_PROTECT to "1")))
        // Undeclared is never armed — an exporter that asks for no password cannot be given one.
        assertFalse(
            ExportOptions.wantsExportSecret(trio, mapOf(ExporterContract.OPTION_PROTECT to "1")),
        )
        // The reserved id declared as another kind is not the reserved option.
        assertFalse(
            ExportOptions.wantsExportSecret(
                info(choice(ExporterContract.OPTION_PROTECT, listOf("1", "0"), "1")),
                emptyMap(),
            ),
        )
        // A value that is neither "0" nor "1" falls back to the default, exactly as specValues would.
        assertFalse(ExportOptions.wantsExportSecret(pdf, mapOf(ExporterContract.OPTION_PROTECT to "yes")))
        assertTrue(
            ExportOptions.wantsExportSecret(
                info(toggle(ExporterContract.OPTION_PROTECT, "1")),
                mapOf(ExporterContract.OPTION_PROTECT to "maybe"),
            ),
        )
    }

    @Test
    fun anExporterThatNeverAskedAboutPaperGetsTheWholePage() {
        // Undeclared means on: full fidelity is what every render has always produced, and only an
        // exporter offering the toggle can ever be handed white ground.
        assertTrue(ExportOptions.includeTemplate(info(), emptyMap()))
        assertTrue(ExportOptions.includeTemplate(trio, mapOf(ExporterContract.OPTION_PAGE_TEMPLATE to "0")))
        assertTrue(ExportOptions.includeTemplate(pdf, emptyMap()))
        assertFalse(ExportOptions.includeTemplate(pdf, mapOf(ExporterContract.OPTION_PAGE_TEMPLATE to "0")))
        assertTrue(ExportOptions.includeTemplate(pdf, mapOf(ExporterContract.OPTION_PAGE_TEMPLATE to "1")))
        // Stale or foreign, it is the declared default again.
        assertTrue(ExportOptions.includeTemplate(pdf, mapOf(ExporterContract.OPTION_PAGE_TEMPLATE to "off")))
    }

    @Test
    fun oneFieldBlockCannotServeTwoSecrets() {
        // The screen has ONE dual masked block, XML-static so a half-typed secret survives a
        // rebuild. An exporter that could arm the rekey passphrase and the export password at once
        // has no drawable panel, so it is dropped whole rather than half-drawn.
        val both = info(
            choice(
                ExporterContract.OPTION_KEYING,
                listOf(ExporterContract.KEYING_KEEP, ExporterContract.KEYING_REKEY),
                ExporterContract.KEYING_KEEP,
            ),
            toggle(ExporterContract.OPTION_PROTECT, "0"),
        )
        assertFalse(ExportOptions.isRenderable(both))
        // Each alone is fine, and so is a keying trio without the rekey choice beside the toggle.
        assertTrue(ExportOptions.isRenderable(trio))
        assertTrue(ExportOptions.isRenderable(pdf))
        assertTrue(
            ExportOptions.isRenderable(
                info(
                    choice(
                        ExporterContract.OPTION_KEYING,
                        listOf(ExporterContract.KEYING_KEEP, ExporterContract.KEYING_PLAIN),
                        ExporterContract.KEYING_KEEP,
                    ),
                    toggle(ExporterContract.OPTION_PROTECT, "0"),
                ),
            ),
        )
    }

    @Test
    fun aReservedOptionOnlyRidesTheSourceKindThatExecutesIt() {
        // Keying is the keyed-artifact path's step and the rendered-pages path never runs it: a
        // SOURCE_PAGES exporter declaring the trio would have its keying UI drawn, collected — and
        // silently discarded (the D3 review). Dropped whole at discovery instead, exactly like a
        // keying choice the host has no transform for. Same the other way for the template toggle,
        // which only the render answers.
        assertFalse(
            ExportOptions.isRenderable(
                pagesInfo(
                    choice(
                        ExporterContract.OPTION_KEYING,
                        listOf(ExporterContract.KEYING_KEEP),
                        ExporterContract.KEYING_KEEP,
                    ),
                ),
            ),
        )
        assertFalse(ExportOptions.isRenderable(info(toggle(ExporterContract.OPTION_PAGE_TEMPLATE, "1"))))
        // Protect is extension-executed and rides either kind.
        assertTrue(ExportOptions.isRenderable(info(toggle(ExporterContract.OPTION_PROTECT, "0"))))
        assertTrue(ExportOptions.isRenderable(pagesInfo(toggle(ExporterContract.OPTION_PROTECT, "0"))))
        // The gate is by reserved id, whatever the kind: even declared as another kind, the id
        // names a step the chosen source kind will never execute.
        assertFalse(ExportOptions.isRenderable(pagesInfo(toggle(ExporterContract.OPTION_KEYING, "0"))))
    }

    // ── The reserved arc-19 format choice (M9) ──

    private fun documentInfo(vararg options: OptionDescriptor) =
        ExporterInfo(
            "Markdown / text document", "md", "text/markdown", options.toList(),
            ExporterContract.SOURCE_DOCUMENT,
        )

    private val textFormat = choice(
        ExporterContract.OPTION_TEXT_FORMAT,
        listOf(ExporterContract.TEXT_FORMAT_MARKDOWN, ExporterContract.TEXT_FORMAT_PLAIN),
        ExporterContract.TEXT_FORMAT_MARKDOWN,
    )

    @Test
    fun textFormatRidesOnlyTheDocumentKindAndOnlyKnownChoices() {
        assertTrue(ExportOptions.isRenderable(documentInfo(textFormat)))
        // A bare document exporter (no options at all) is renderable too — the option is offered,
        // not owed.
        assertTrue(ExportOptions.isRenderable(documentInfo()))
        // The format choice is host-executed (assembly + destination naming): on another source
        // kind there is no assembly to choose, and an unknown choice id is a step the host cannot
        // take — both dropped whole at discovery, the keying precedent.
        assertFalse(ExportOptions.isRenderable(info(textFormat)))
        assertFalse(ExportOptions.isRenderable(pagesInfo(textFormat)))
        assertFalse(
            ExportOptions.isRenderable(
                documentInfo(
                    choice(
                        ExporterContract.OPTION_TEXT_FORMAT,
                        listOf(ExporterContract.TEXT_FORMAT_MARKDOWN, "rtf"),
                        ExporterContract.TEXT_FORMAT_MARKDOWN,
                    ),
                ),
            ),
        )
        // Keying can never ride the document kind — its transforms belong to the `.soil` path.
        assertFalse(
            ExportOptions.isRenderable(
                documentInfo(
                    choice(
                        ExporterContract.OPTION_KEYING,
                        listOf(ExporterContract.KEYING_KEEP),
                        ExporterContract.KEYING_KEEP,
                    ),
                ),
            ),
        )
    }

    @Test
    fun textFormatAnswersLikeTheSpecAndDefaultsToMarkdown() {
        val i = documentInfo(textFormat)
        assertEquals(ExporterContract.TEXT_FORMAT_MARKDOWN, ExportOptions.textFormat(i, emptyMap()))
        assertEquals(
            ExporterContract.TEXT_FORMAT_PLAIN,
            ExportOptions.textFormat(i, mapOf(ExporterContract.OPTION_TEXT_FORMAT to ExporterContract.TEXT_FORMAT_PLAIN)),
        )
        // A stale or foreign value falls back to the declared default — exactly what the spec
        // would carry.
        assertEquals(
            ExporterContract.TEXT_FORMAT_MARKDOWN,
            ExportOptions.textFormat(i, mapOf(ExporterContract.OPTION_TEXT_FORMAT to "rtf")),
        )
        // Undeclared means Markdown: an exporter that never asked gets the document as it is.
        assertEquals(ExporterContract.TEXT_FORMAT_MARKDOWN, ExportOptions.textFormat(documentInfo(), emptyMap()))
    }

    @Test
    fun theSecretRidesItsOwnCarrierAndNeverTheValueMap() {
        // The map is the whole of what the panel can send; the password is handed over separately,
        // on ExportSpec's carrier. Whatever the screen's state got up to, nothing here can carry it.
        val armed = ExportOptions.specValues(pdf, mapOf(ExporterContract.OPTION_PROTECT to "1"))
        assertEquals(
            mapOf(ExporterContract.OPTION_PAGE_TEMPLATE to "1", ExporterContract.OPTION_PROTECT to "1"),
            armed,
        )
        // A secret smuggled in under any id — the reserved one included — is not a declared option.
        val smuggled = ExportOptions.specValues(
            pdf,
            mapOf(ExporterContract.OPTION_PROTECT to "1", "password" to "hunter2"),
        )
        assertEquals(setOf(ExporterContract.OPTION_PAGE_TEMPLATE, ExporterContract.OPTION_PROTECT), smuggled.keys)
        assertTrue(smuggled.values.none { it == "hunter2" })
    }
}
