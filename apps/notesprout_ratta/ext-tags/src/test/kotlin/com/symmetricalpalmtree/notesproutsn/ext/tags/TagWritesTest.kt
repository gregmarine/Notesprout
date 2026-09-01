package com.symmetricalpalmtree.notesproutsn.ext.tags

import com.symmetricalpalmtree.notesproutsn.extension.ExtensionContract
import com.symmetricalpalmtree.notesproutsn.extension.TagCodec
import com.symmetricalpalmtree.notesproutsn.extension.TagIndex
import com.symmetricalpalmtree.notesproutsn.extension.TagShowing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one read-modify-write both writers share (arc 21 / W1). This is the JVM cover for the
 * `snapshot` / `assign` half of the service and for every edit the screen makes — neither of those
 * classes can run off-device, and this is the logic inside both of them.
 */
class TagWritesTest {

    /** Since W4 a target is a notebook, plus a page when the tag is on one. */
    private val n1 = "11111111-1111-4111-8111-111111111111"
    private val p1 = "aaaaaaaa-1111-4111-8111-111111111111"

    private fun fresh(): Pair<FakeExtensionStore, TagStore> {
        val fake = FakeExtensionStore()
        return fake to TagStore(fake)
    }

    private fun written(outcome: TagWrites.Outcome): TagIndex {
        assertTrue("expected Written, got $outcome", outcome is TagWrites.Outcome.Written)
        return (outcome as TagWrites.Outcome.Written).index
    }

    private fun failedWith(outcome: TagWrites.Outcome): TagWrites.Reason {
        assertTrue("expected Failed, got $outcome", outcome is TagWrites.Outcome.Failed)
        return (outcome as TagWrites.Outcome.Failed).reason
    }

    /** The service's `assign`, end to end: create, attach, write, answer with the canonical form. */
    @Test
    fun assignCreatesAttachesAndWrites() {
        val (fake, store) = fresh()
        val index = written(TagWrites.apply(store) { it.assign("Reading List", n1).index })
        assertEquals(listOf("Reading List"), index.tags.map { it.display })
        // Written, not merely held in memory: the store carries it before anyone is told.
        assertEquals(
            listOf("Reading List"),
            TagCodec.decode(fake.values[TagStore.KEY_INDEX]).tags.map { it.display },
        )
    }

    /** Two writers, one value: the second cycle must apply to what the first wrote, not to what its
     *  caller was holding. This is the whole reason the read is inside the lock. */
    @Test
    fun aSecondEditAppliesToWhatTheFirstWrote() {
        val (_, store) = fresh()
        val stale = TagIndex.EMPTY
        TagWrites.apply(store) { it.assign("draft", n1).index }
        // The caller still holds `stale` — and the transform is handed the FRESH index anyway.
        val after = written(
            TagWrites.apply(store) { current ->
                assertEquals(1, current.tags.size)
                assertEquals(0, stale.tags.size)
                current.assign("done", n1).index
            },
        )
        assertEquals(2, after.tags.size)
    }

    @Test
    fun nothingToDoIsUnchangedAndStillHandsBackTheFreshIndex() {
        val (fake, store) = fresh()
        TagWrites.apply(store) { it.assign("draft", n1).index }
        val before = fake.values[TagStore.KEY_INDEX]!!.copyOf()
        val outcome = TagWrites.apply(store) { null }
        assertTrue(outcome is TagWrites.Outcome.Unchanged)
        assertEquals(1, (outcome as TagWrites.Outcome.Unchanged).index.tags.size)
        // Nothing was written — an unchanged cycle must not re-stamp the value.
        assertTrue(before.contentEquals(fake.values[TagStore.KEY_INDEX]))
    }

    @Test
    fun textThatIsNotATagIsItsOwnReason() {
        val (_, store) = fresh()
        assertEquals(
            TagWrites.Reason.NOT_A_TAG,
            failedWith(TagWrites.apply(store) { it.assign("   ", n1).index }),
        )
    }

    @Test
    fun aCapIsItsOwnReasonAndWritesNothing() {
        val (fake, store) = fresh()
        var full = TagIndex.EMPTY
        for (n in 0 until ExtensionContract.MAX_TAGS) full = full.assign("tag $n", n1).index
        store.write(full)
        val before = fake.values[TagStore.KEY_INDEX]!!.copyOf()
        assertEquals(
            TagWrites.Reason.INDEX_FULL,
            failedWith(TagWrites.apply(store) { it.assign("one too many", n1).index }),
        )
        assertTrue(before.contentEquals(fake.values[TagStore.KEY_INDEX]))
    }

    /** Unreadable is refused as itself and — the point — nothing is written over it. */
    @Test
    fun anUnreadableIndexIsNeverOverwritten() {
        val fake = FakeExtensionStore()
        val poison = "NSTAG9\nT\t0\tdraft\n".toByteArray(Charsets.UTF_8)
        fake.values[TagStore.KEY_INDEX] = poison
        val outcome = TagWrites.apply(TagStore(fake)) { TagIndex.EMPTY }
        assertEquals(TagWrites.Reason.INDEX_UNREADABLE, failedWith(outcome))
        assertTrue(poison.contentEquals(fake.values[TagStore.KEY_INDEX]))
    }

    @Test
    fun aStoreThatCannotBeReachedIsUnavailable() {
        val fake = FakeExtensionStore()
        fake.failWith = { SecurityException("revoked") }
        assertEquals(
            TagWrites.Reason.STORE_UNAVAILABLE,
            failedWith(TagWrites.apply(TagStore(fake)) { it }),
        )
    }

    /** The screen's other three edits, over the same cycle. */
    @Test
    fun removeAndDeleteGoThroughTheSameCycle() {
        val (_, store) = fresh()
        var index = written(TagWrites.apply(store) { it.assign("draft", n1).index })
        index = written(TagWrites.apply(store) { it.assign("draft", n1, p1).index })
        val draft = index.find("draft")!!.id

        index = written(TagWrites.apply(store) { it.unassign(draft, n1, p1) })
        // The tag persists until it is explicitly deleted — that is the lifecycle call.
        assertEquals(1, index.tags.size)
        assertEquals(1, index.assignments.size)

        index = written(TagWrites.apply(store) { it.deleteTag(draft) })
        assertEquals(0, index.tags.size)
        assertEquals(0, index.assignments.size)
    }
}
