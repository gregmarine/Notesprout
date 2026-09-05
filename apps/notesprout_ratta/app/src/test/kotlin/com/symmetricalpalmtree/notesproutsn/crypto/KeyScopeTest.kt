package com.symmetricalpalmtree.notesproutsn.crypto

import com.symmetricalpalmtree.notesproutsn.data.soil.KEY_SCOPE_GLOBAL
import com.symmetricalpalmtree.notesproutsn.data.soil.KEY_SCOPE_NOTEBOOK
import org.junit.Assert.assertEquals
import org.junit.Test

class KeyScopeTest {
    @Test fun columnRoundTrip() {
        assertEquals(KeyScope.GLOBAL, KeyScope.of(KEY_SCOPE_GLOBAL))
        assertEquals(KeyScope.NOTEBOOK, KeyScope.of(KEY_SCOPE_NOTEBOOK))
        assertEquals(KEY_SCOPE_GLOBAL, KeyScope.GLOBAL.column)
        assertEquals(KEY_SCOPE_NOTEBOOK, KeyScope.NOTEBOOK.column)
    }

    @Test fun anythingElseIsGlobal() {
        assertEquals(KeyScope.GLOBAL, KeyScope.of(null))
        assertEquals(KeyScope.GLOBAL, KeyScope.of(""))
        assertEquals(KeyScope.GLOBAL, KeyScope.of("notebook"))
    }
}
