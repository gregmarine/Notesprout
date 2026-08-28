package com.symmetricalpalmtree.notesproutsn.ext.soil

import com.symmetricalpalmtree.notesproutsn.extension.ExporterContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** The one rule the exporter enforces on its way in — refuse what was never offered, ignore the rest. */
class SoilExportSpecTest {

    @Test
    fun anAbsentKeyingEntryMeansKeep() {
        assertEquals(ExporterContract.KEYING_KEEP, SoilExportSpec.keying(emptyMap()))
    }

    @Test
    fun everyDeclaredChoiceIsAccepted() {
        for (keying in listOf(
            ExporterContract.KEYING_KEEP,
            ExporterContract.KEYING_REKEY,
            ExporterContract.KEYING_PLAIN,
        )) {
            assertEquals(keying, SoilExportSpec.keying(mapOf(ExporterContract.OPTION_KEYING to keying)))
        }
    }

    @Test
    fun aKeyingThisBuildNeverOfferedIsRefused() {
        // An IllegalArgumentException is the refusal that actually reaches the host.
        assertThrows(IllegalArgumentException::class.java) {
            SoilExportSpec.keying(mapOf(ExporterContract.OPTION_KEYING to "nonsense"))
        }
        // Empty is a value, not an absence: it is not a declared choice either.
        assertThrows(IllegalArgumentException::class.java) {
            SoilExportSpec.keying(mapOf(ExporterContract.OPTION_KEYING to ""))
        }
        // A secret sent where a choice id belongs is refused, never acted on — the host never
        // sends one, so a value shaped like free text is by definition not on offer.
        assertThrows(IllegalArgumentException::class.java) {
            SoilExportSpec.keying(mapOf(ExporterContract.OPTION_KEYING to "hunter2"))
        }
    }

    @Test
    fun unknownKeysAreIgnored() {
        // Forward-compat: a newer host may send options a newer descriptor declared, and nothing
        // here is entitled to refuse an export over a key it does not know.
        assertEquals(
            ExporterContract.KEYING_KEEP,
            SoilExportSpec.keying(mapOf("verify" to "1", "compress" to "0")),
        )
        assertEquals(
            ExporterContract.KEYING_KEEP,
            SoilExportSpec.keying(
                mapOf(ExporterContract.OPTION_KEYING to ExporterContract.KEYING_KEEP, "future" to "x"),
            ),
        )
    }

    @Test
    fun theFullTrioIsSupported() {
        assertEquals(
            setOf(
                ExporterContract.KEYING_KEEP,
                ExporterContract.KEYING_REKEY,
                ExporterContract.KEYING_PLAIN,
            ),
            SoilExportSpec.SUPPORTED_KEYING,
        )
    }
}
