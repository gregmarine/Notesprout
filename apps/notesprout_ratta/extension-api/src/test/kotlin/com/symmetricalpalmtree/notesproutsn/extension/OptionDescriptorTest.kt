package com.symmetricalpalmtree.notesproutsn.extension

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** The `require`s only — a Parcel round trip needs a device (`:extension-api` runs no Robolectric). */
class OptionDescriptorTest {

    private fun singleChoice(
        id: String = "keying",
        label: String = "Encryption",
        choiceIds: List<String> = listOf("keep", "rekey", "plain"),
        choiceLabels: List<String> = listOf("Keep encrypted", "New passphrase…", "Remove encryption"),
        defaultValue: String = "keep",
    ) = OptionDescriptor(id, label, ExporterContract.KIND_SINGLE_CHOICE, choiceIds, choiceLabels, defaultValue)

    @Test
    fun acceptsTheKeyingTrio() {
        val d = singleChoice()
        assertEquals("keying", d.id)
        assertEquals(3, d.choiceIds.size)
        assertEquals("keep", d.defaultValue)
    }

    @Test
    fun acceptsToggleAndPassphrase() {
        OptionDescriptor("verify", "Verify after export", ExporterContract.KIND_TOGGLE, emptyList(), emptyList(), "1")
        OptionDescriptor("pw", "Password", ExporterContract.KIND_PASSPHRASE, emptyList(), emptyList(), "")
    }

    @Test
    fun rejectsBadIds() {
        assertThrows(IllegalArgumentException::class.java) { singleChoice(id = "") }
        assertThrows(IllegalArgumentException::class.java) { singleChoice(id = "has space") }
        assertThrows(IllegalArgumentException::class.java) { singleChoice(id = "a".repeat(ExporterContract.MAX_ID_CHARS + 1)) }
        assertThrows(IllegalArgumentException::class.java) { singleChoice(choiceIds = listOf("ok", "not/ok", "x"), defaultValue = "ok") }
    }

    @Test
    fun rejectsBadLabels() {
        assertThrows(IllegalArgumentException::class.java) { singleChoice(label = " ") }
        assertThrows(IllegalArgumentException::class.java) { singleChoice(label = "x".repeat(ExporterContract.MAX_LABEL_CHARS + 1)) }
    }

    @Test
    fun rejectsUnknownKind() {
        assertThrows(IllegalArgumentException::class.java) {
            OptionDescriptor("x", "X", 3, emptyList(), emptyList(), "")
        }
    }

    @Test
    fun singleChoiceRejectsMalformedChoices() {
        // No choices at all.
        assertThrows(IllegalArgumentException::class.java) { singleChoice(choiceIds = emptyList(), choiceLabels = emptyList()) }
        // Id/label count mismatch.
        assertThrows(IllegalArgumentException::class.java) { singleChoice(choiceLabels = listOf("only one")) }
        // Duplicate ids.
        assertThrows(IllegalArgumentException::class.java) {
            singleChoice(choiceIds = listOf("keep", "keep", "plain"))
        }
        // Over the cap.
        val many = (0..ExporterContract.MAX_CHOICES).map { "c$it" }
        assertThrows(IllegalArgumentException::class.java) {
            singleChoice(choiceIds = many, choiceLabels = many, defaultValue = "c0")
        }
        // Default not among the choices.
        assertThrows(IllegalArgumentException::class.java) { singleChoice(defaultValue = "absent") }
    }

    @Test
    fun toggleAndPassphraseRejectChoicesAndBadDefaults() {
        assertThrows(IllegalArgumentException::class.java) {
            OptionDescriptor("t", "T", ExporterContract.KIND_TOGGLE, listOf("a"), listOf("A"), "1")
        }
        assertThrows(IllegalArgumentException::class.java) {
            OptionDescriptor("t", "T", ExporterContract.KIND_TOGGLE, emptyList(), emptyList(), "yes")
        }
        assertThrows(IllegalArgumentException::class.java) {
            OptionDescriptor("pw", "PW", ExporterContract.KIND_PASSPHRASE, emptyList(), emptyList(), "secret")
        }
    }
}
