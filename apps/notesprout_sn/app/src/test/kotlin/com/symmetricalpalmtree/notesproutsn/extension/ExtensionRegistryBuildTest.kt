package com.symmetricalpalmtree.notesproutsn.extension

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The host-side half of the build rule: a `.dev` host pairs only with `.dev` extensions and the
 * release host only with release ones, even though both builds share one signing key on a
 * developer's device (the extension side is `HostCallerCheck` + a per-build `HOST_PACKAGE`).
 */
class ExtensionRegistryBuildTest {

    private val release = "com.symmetricalpalmtree.notesproutsn"
    private val dev = "$release.dev"

    @Test
    fun releaseHostKeepsReleaseExtensions() {
        assertTrue(ExtensionRegistry.sameBuild(release, "$release.ext.bible"))
        assertTrue(ExtensionRegistry.sameBuild(release, "$release.ext.mlkit"))
    }

    @Test
    fun devHostKeepsDevExtensions() {
        assertTrue(ExtensionRegistry.sameBuild(dev, "$release.ext.bible.dev"))
        assertTrue(ExtensionRegistry.sameBuild(dev, "$release.ext.mlkit.dev"))
    }

    @Test
    fun releaseHostSkipsDevExtensions() {
        assertFalse(ExtensionRegistry.sameBuild(release, "$release.ext.bible.dev"))
    }

    @Test
    fun devHostSkipsReleaseExtensions() {
        assertFalse(ExtensionRegistry.sameBuild(dev, "$release.ext.bible"))
    }

    @Test
    fun devIsASuffixNotASubstring() {
        // A package that merely contains "dev" in its name is not a dev build.
        assertFalse(ExtensionRegistry.sameBuild(dev, "$release.ext.devotional"))
        assertTrue(ExtensionRegistry.sameBuild(release, "$release.ext.devotional"))
    }
}
