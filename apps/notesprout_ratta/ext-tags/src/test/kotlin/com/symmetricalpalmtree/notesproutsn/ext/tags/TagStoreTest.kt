package com.symmetricalpalmtree.notesproutsn.ext.tags

import com.symmetricalpalmtree.notesproutsn.extension.TagCodec
import com.symmetricalpalmtree.notesproutsn.extension.TagIndex
import com.symmetricalpalmtree.notesproutsn.extension.TagShowing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The tag index's key layout over the host's store (arc 21 / W1), against a fake store. */
class TagStoreTest {

    /** Since W4 a target is a notebook, plus a page when the tag is on one. */
    private val n1 = "11111111-1111-4111-8111-111111111111"

    @Test
    fun firstRunIsEmptyNotAFailure() {
        val store = TagStore(FakeExtensionStore())
        assertNull(store.readBlob())
        assertEquals(0, store.read().tags.size)
    }

    @Test
    fun writeThenReadRoundTrips() {
        val fake = FakeExtensionStore()
        val store = TagStore(fake)
        val index = TagIndex.EMPTY.assign("reading list", n1).index
        store.write(index)
        assertEquals(setOf(TagStore.KEY_INDEX), fake.values.keys)
        val back = store.read()
        assertEquals(listOf("reading list"), back.tags.map { it.display })
        assertEquals(1, back.assignments.size)
    }

    /** Unreadable is not empty: writing over it would lose a library's tags, so it is its own error. */
    @Test
    fun anUnreadableValueIsItsOwnFailure() {
        val fake = FakeExtensionStore()
        fake.values[TagStore.KEY_INDEX] = "NSTAG9\nT\t0\tdraft\n".toByteArray(Charsets.UTF_8)
        try {
            TagStore(fake).read()
            fail("expected IndexUnreadable")
        } catch (expected: IndexUnreadable) {
        }
    }

    @Test
    fun anyStoreFailureIsUnavailable() {
        val fake = FakeExtensionStore()
        fake.failWith = { SecurityException("revoked") }
        try {
            TagStore(fake).read()
            fail("expected StoreUnavailable")
        } catch (expected: StoreUnavailable) {
        }
        try {
            TagStore(fake).write(TagIndex.EMPTY)
            fail("expected StoreUnavailable")
        } catch (expected: StoreUnavailable) {
        }
    }

    /** A blob past the inline cap is what sends a read to the large path — the exact message, never
     *  a substring. The JVM has no ashmem, so this proves the fork, not the transfer. */
    @Test
    fun anOversizeValueTakesTheLargePath() {
        val fake = FakeExtensionStore()
        // Bypass `put`'s own cap the way the real store's large path does.
        fake.values[TagStore.KEY_INDEX] = ByteArray(600 * 1024) { 'x'.code.toByte() }
        try {
            TagStore(fake).readBlob()
            fail("expected the large path (which the JVM cannot run)")
        } catch (expected: StoreUnavailable) {
            assertTrue(expected.cause is IllegalStateException)
        }
    }

    @Test
    fun theStoredBytesAreExactlyTheCodecBlob() {
        val fake = FakeExtensionStore()
        val index = TagIndex.EMPTY.assign("draft", n1).index
        TagStore(fake).write(index)
        // The wire form IS the storage form — `snapshot` hands the host this value untouched.
        assertTrue(TagCodec.encode(index).contentEquals(fake.values[TagStore.KEY_INDEX]))
    }
}
