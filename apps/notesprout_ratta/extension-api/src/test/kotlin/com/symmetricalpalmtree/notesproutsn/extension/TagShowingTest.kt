package com.symmetricalpalmtree.notesproutsn.extension

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

/**
 * The showing parcelable's constructor `require`s — unmarshal is the validation (family rule). A
 * real `Parcel` round trip is not available here (`:extension-api` runs plain JVM tests with no
 * Robolectric), so what is pinned is the gate either side of the wire.
 */
class TagShowingTest {

    private val n1 = "11111111-1111-4111-8111-111111111111"
    private val p1 = "aaaaaaaa-1111-4111-8111-111111111111"

    private fun showing(
        notebookId: String = n1,
        pageId: String? = null,
        targetLabel: String = "Journal",
        mode: Int = TagShowing.MODE_BROWSE,
        prefill: String? = null,
        pageIds: List<String> = emptyList(),
        pageLabels: List<String> = emptyList(),
    ) = TagShowing(notebookId, pageId, targetLabel, mode, prefill, pageIds, pageLabels)

    private fun assertRefused(build: () -> TagShowing) {
        try {
            build()
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
        }
    }

    @Test
    fun acceptsTheThreeModes() {
        assertEquals(TagShowing.MODE_BROWSE, showing(mode = TagShowing.MODE_BROWSE).mode)
        assertEquals(TagShowing.MODE_ADD, showing(mode = TagShowing.MODE_ADD).mode)
        assertEquals(
            TagShowing.MODE_MANAGE,
            showing(mode = TagShowing.MODE_MANAGE, pageIds = listOf(p1), pageLabels = listOf("Page 1")).mode,
        )
    }

    /** The kind is derived, never carried (arc 21 / W4): a page id present is what makes a page
     *  showing, and `targetId` is whichever of the two the tags actually hang on. */
    @Test
    fun theKindFallsOutOfThePair() {
        val notebook = showing()
        assertEquals(TagShowing.TARGET_NOTEBOOK, notebook.targetKind)
        assertEquals(n1, notebook.targetId)
        assertNull(notebook.pageId)

        val page = showing(pageId = p1)
        assertEquals(TagShowing.TARGET_PAGE, page.targetKind)
        assertEquals(p1, page.targetId)
        assertEquals(n1, page.notebookId)
    }

    @Test
    fun refusesUnknownMode() {
        assertRefused { showing(mode = 7) }
    }

    /** Both ids are canonical UUIDs, which is also what keeps a path character out of them. */
    @Test
    fun refusesBadTargets() {
        assertRefused { showing(notebookId = "") }
        assertRefused { showing(notebookId = "n1") }
        assertRefused { showing(notebookId = "x".repeat(64)) }
        assertRefused { showing(notebookId = "a/b") }
        assertRefused { showing(pageId = "p1") }
        assertRefused { showing(targetLabel = "x".repeat(ExtensionContract.MAX_TARGET_LABEL_CHARS + 1)) }
    }

    @Test
    fun refusesAPrefillThatCouldNotBeATag() {
        assertRefused { showing(prefill = "x".repeat(ExtensionContract.MAX_TAG_CHARS + 1)) }
        // A blank prefill is legal: recognition can hand back nothing, and the field simply opens empty.
        assertEquals("", showing(prefill = "").prefill)
    }

    /** Pages belong to MANAGE and to a notebook — a page's own screen has nothing to page through. */
    @Test
    fun pagesBelongToManageOnly() {
        assertRefused { showing(mode = TagShowing.MODE_BROWSE, pageIds = listOf(p1), pageLabels = listOf("Page 1")) }
        assertRefused {
            showing(
                pageId = p1,
                mode = TagShowing.MODE_MANAGE, pageIds = listOf(p1), pageLabels = listOf("Page 1"),
            )
        }
        assertRefused { showing(mode = TagShowing.MODE_MANAGE, pageIds = listOf(p1), pageLabels = emptyList()) }
        assertRefused {
            showing(
                mode = TagShowing.MODE_MANAGE,
                pageIds = List(TagShowing.MAX_PAGES + 1) { p1 },
                pageLabels = List(TagShowing.MAX_PAGES + 1) { "Page $it" },
            )
        }
        // A listed page that is not a UUID is refused with the rest.
        assertRefused {
            showing(mode = TagShowing.MODE_MANAGE, pageIds = listOf("p1"), pageLabels = listOf("Page 1"))
        }
    }

    @Test
    fun constantsAreStable() {
        assertEquals(0, TagShowing.TARGET_NOTEBOOK)
        assertEquals(1, TagShowing.TARGET_PAGE)
        assertEquals(0, TagShowing.MODE_BROWSE)
        assertEquals(1, TagShowing.MODE_ADD)
        assertEquals(2, TagShowing.MODE_MANAGE)
    }
}
