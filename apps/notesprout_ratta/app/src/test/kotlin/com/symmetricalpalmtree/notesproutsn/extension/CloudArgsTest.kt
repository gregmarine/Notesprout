package com.symmetricalpalmtree.notesproutsn.extension

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The host's own checks on the cloud seam (arc 25 / V2) — the half of [CloudClient] a JVM can run.
 * The binder cannot, so what is tested is the promise that matters most: **a refusal never costs a
 * bind**, because every one of these runs before the client goes anywhere near a service.
 */
class CloudArgsTest {

    private fun refuses(block: () -> Unit) = assertThrows(ExtensionCallException::class.java) { block() }

    // ── Paths ──────

    @Test
    fun `an empty path is the root and is legal`() {
        CloudArgs.requirePath(emptyArray())
    }

    @Test
    fun `a path at the depth cap passes and one over it is refused`() {
        CloudArgs.requirePath(Array(CloudContract.MAX_PATH_DEPTH) { "f$it" })
        refuses { CloudArgs.requirePath(Array(CloudContract.MAX_PATH_DEPTH + 1) { "f$it" }) }
    }

    @Test
    fun `a segment that is not a name is refused`() {
        refuses { CloudArgs.requirePath(arrayOf("Exports", "")) }
        refuses { CloudArgs.requirePath(arrayOf("Exports", "a/b")) }
        refuses { CloudArgs.requirePath(arrayOf("Exports", "a\\b")) }
        refuses { CloudArgs.requirePath(arrayOf("Exports", "..")) }
        refuses { CloudArgs.requirePath(arrayOf(" leading")) }
        refuses { CloudArgs.requirePath(arrayOf("trailing ")) }
        // A control character, spelled as an escape — never typed raw into a source file.
        refuses { CloudArgs.requirePath(arrayOf("a\u0007b")) }
        refuses { CloudArgs.requirePath(arrayOf("x".repeat(CloudContract.MAX_NAME_CHARS + 1))) }
    }

    // ── Names, MIME types, ids, byte counts ──────

    @Test
    fun `a name is one name`() {
        CloudArgs.requireName("notebook.soil")
        refuses { CloudArgs.requireName("") }
        refuses { CloudArgs.requireName("a/b.soil") }
    }

    @Test
    fun `a mime type is type slash subtype`() {
        CloudArgs.requireMime("application/octet-stream")
        refuses { CloudArgs.requireMime("application") }
        refuses { CloudArgs.requireMime("application/") }
        refuses { CloudArgs.requireMime("/octet-stream") }
        refuses { CloudArgs.requireMime("a/b/c") }
        refuses { CloudArgs.requireMime("application/octet stream") }
    }

    @Test
    fun `an entry id is opaque but not blank or spaced`() {
        CloudArgs.requireEntryId("1A2b3C-_xyz")
        refuses { CloudArgs.requireEntryId("") }
        refuses { CloudArgs.requireEntryId("has space") }
        refuses { CloudArgs.requireEntryId("x".repeat(CloudContract.MAX_ENTRY_ID_CHARS + 1)) }
    }

    @Test
    fun `an empty file is a file, a negative byte count is not`() {
        CloudArgs.requireExpectedBytes(0)
        CloudArgs.requireExpectedBytes(20L * 1024 * 1024)
        refuses { CloudArgs.requireExpectedBytes(-1) }
    }

    // ── Replies ──────

    private fun file(name: String) = CloudEntry("id-$name", name, isFolder = false, sizeBytes = 7, modifiedAt = 1)
    private fun folder(name: String) = CloudEntry("id-$name", name, isFolder = true, sizeBytes = 0, modifiedAt = 1)

    @Test
    fun `a listing is kept, a missing one and an oversized one are refused`() {
        assertEquals(2, CloudArgs.checkList(arrayOf(folder("a"), file("b.pdf"))).size)
        assertEquals(0, CloudArgs.checkList(emptyArray()).size)
        refuses { CloudArgs.checkList(null) }
        refuses { CloudArgs.checkList(Array(CloudContract.MAX_LIST_ENTRIES + 1) { file("f$it") }) }
    }

    @Test
    fun `ensureFolder must answer a folder`() {
        val f = folder("probe")
        assertSame(f, CloudArgs.checkFolder(f))
        refuses { CloudArgs.checkFolder(null) }
        refuses { CloudArgs.checkFolder(file("probe")) }
    }

    @Test
    fun `upload must answer a file`() {
        val f = file("probe.bin")
        assertSame(f, CloudArgs.checkUploaded(f))
        refuses { CloudArgs.checkUploaded(null) }
        refuses { CloudArgs.checkUploaded(folder("probe")) }
    }

    @Test
    fun `a size the provider disagrees about is NOT refused here`() {
        // Corroboration is the caller's, and disagreement means "check the file", never a refusal
        // and never a delete (the arc-15 rule; a provider's metadata can lag its own write).
        val reported = CloudEntry("id", "probe.bin", isFolder = false, sizeBytes = 0, modifiedAt = 0)
        assertSame(reported, CloudArgs.checkUploaded(reported))
    }

    @Test
    fun `a download reports a count that cannot be negative`() {
        assertEquals(0L, CloudArgs.checkDownloaded(0))
        assertEquals(99L, CloudArgs.checkDownloaded(99))
        refuses { CloudArgs.checkDownloaded(-1) }
    }
}
