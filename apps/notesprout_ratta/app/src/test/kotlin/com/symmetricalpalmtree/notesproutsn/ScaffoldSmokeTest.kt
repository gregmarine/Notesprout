package com.symmetricalpalmtree.notesproutsn

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * R0 scaffold smoke test — proves the JVM test harness (JUnit4 over the Kotlin/JDK toolchain, no
 * Android framework, no dependencies) runs green before any real logic exists. A trivial pure
 * round-trip stands in for it.
 */
class ScaffoldSmokeTest {

    private fun reverse(s: String): String = s.reversed()

    @Test
    fun `reversing twice returns the original string`() {
        val original = "Notesprout SN"
        assertEquals(original, reverse(reverse(original)))
    }
}
