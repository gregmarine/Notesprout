package com.symmetricalpalmtree.notesproutsn.extension

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/**
 * The showing parcelable's constructor `require`s — unmarshal is the validation (family rule). A
 * real `Parcel` round trip is not available here (`:extension-api` runs plain JVM tests with no
 * Robolectric), so what is pinned is the gate either side of the wire.
 */
class TagShowingTest {

    private fun showing(
        targetKind: Int = TagShowing.TARGET_NOTEBOOK,
        targetId: String = "n1",
        targetLabel: String = "Journal",
        mode: Int = TagShowing.MODE_BROWSE,
        prefill: String? = null,
        pageIds: List<String> = emptyList(),
        pageLabels: List<String> = emptyList(),
    ) = TagShowing(targetKind, targetId, targetLabel, mode, prefill, pageIds, pageLabels)

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
            showing(mode = TagShowing.MODE_MANAGE, pageIds = listOf("p1"), pageLabels = listOf("Page 1")).mode,
        )
    }

    @Test
    fun refusesUnknownKindAndMode() {
        assertRefused { showing(targetKind = 7) }
        assertRefused { showing(mode = 7) }
    }

    @Test
    fun refusesBadTargets() {
        assertRefused { showing(targetId = "") }
        assertRefused { showing(targetId = "x".repeat(ExtensionContract.MAX_TARGET_ID_CHARS + 1)) }
        assertRefused { showing(targetId = "a/b") }
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
        assertRefused { showing(mode = TagShowing.MODE_BROWSE, pageIds = listOf("p1"), pageLabels = listOf("Page 1")) }
        assertRefused {
            showing(
                targetKind = TagShowing.TARGET_PAGE, targetId = "p1",
                mode = TagShowing.MODE_MANAGE, pageIds = listOf("p1"), pageLabels = listOf("Page 1"),
            )
        }
        assertRefused { showing(mode = TagShowing.MODE_MANAGE, pageIds = listOf("p1"), pageLabels = emptyList()) }
        assertRefused {
            showing(
                mode = TagShowing.MODE_MANAGE,
                pageIds = List(TagShowing.MAX_PAGES + 1) { "p$it" },
                pageLabels = List(TagShowing.MAX_PAGES + 1) { "Page $it" },
            )
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
