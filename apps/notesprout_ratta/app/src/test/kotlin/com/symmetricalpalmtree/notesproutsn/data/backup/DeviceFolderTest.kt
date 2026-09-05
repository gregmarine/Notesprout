package com.symmetricalpalmtree.notesproutsn.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import com.symmetricalpalmtree.notesproutsn.extension.CloudContract
import com.symmetricalpalmtree.notesproutsn.library.NameRules
import org.junit.Test

/**
 * The device folder's default name (arc 25 / V4). The whole point of pinning it here is that the
 * name goes into someone else's cloud and then stays there: a rule that quietly changed would
 * split one device's backups across two folders.
 */
class DeviceFolderTest {

    @Test
    fun `a plain model keeps its shape`() {
        assertEquals("A6X2-1a2b3c4d", DeviceFolder.name("A6X2", "1a2b3c4d"))
    }

    @Test
    fun `runs of anything outside the charset collapse to one dash`() {
        assertEquals("Supernote-Nomad-abcd1234", DeviceFolder.name("Supernote  Nomad", "abcd1234"))
        assertEquals("N5-X-abcd1234", DeviceFolder.name("N5/(X)", "abcd1234"))
    }

    @Test
    fun `the ends are trimmed`() {
        assertEquals("Nomad-abcd1234", DeviceFolder.name("  Nomad  ", "abcd1234"))
        assertEquals("Nomad-abcd1234", DeviceFolder.name("...Nomad...", "abcd1234"))
    }

    @Test
    fun `underscores and dashes survive as themselves`() {
        assertEquals("a_b-c-abcd1234", DeviceFolder.name("a_b-c", "abcd1234"))
    }

    @Test
    fun `a model that leaves nothing becomes the fallback`() {
        assertEquals("device-abcd1234", DeviceFolder.name("", "abcd1234"))
        assertEquals("device-abcd1234", DeviceFolder.name(null, "abcd1234"))
        assertEquals("device-abcd1234", DeviceFolder.name("///", "abcd1234"))
    }

    @Test
    fun `a suffix that leaves nothing is simply absent`() {
        assertEquals("Nomad", DeviceFolder.name("Nomad", ""))
        assertEquals("Nomad", DeviceFolder.name("Nomad", "//"))
    }

    @Test
    fun `a long model is cut, and the cut never leaves a trailing dash`() {
        val name = DeviceFolder.name("x".repeat(400), "abcd1234")
        assertEquals("x".repeat(DeviceFolder.MAX_MODEL_CHARS) + "-abcd1234", name)
        assertEquals("Supernote-A-abcd1234", DeviceFolder.name("Supernote A" + " ".repeat(80), "abcd1234"))
    }

    @Test
    fun `the suffix is eight hex characters and differs run to run`() {
        val a = DeviceFolder.randomSuffix()
        val b = DeviceFolder.randomSuffix()
        assertEquals(DeviceFolder.SUFFIX_CHARS, a.length)
        assertTrue(a.all { it in "0123456789abcdef" })
        assertTrue("two mints collided", a != b)
    }

    /**
     * The name has to be legal at both ends of the trip: the family's own charset (so Rename…
     * cannot be refused for the name the app itself minted) and the cloud contract's bounds (so the
     * folder can actually be created).
     */
    @Test
    fun `every minted name is a legal name on both sides`() {
        for (model in listOf("A6X2", "Supernote  Nomad", "", "///", "x".repeat(400), "N5/(X)")) {
            val name = DeviceFolder.name(model, DeviceFolder.randomSuffix())
            assertTrue("NameRules refused $name", NameRules.isValid(name))
            assertTrue("the seam refused $name", CloudContract.isName(name))
        }
    }
}
