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
}
