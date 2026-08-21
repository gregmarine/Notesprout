package com.symmetricalpalmtree.notesprout.ext.links

import com.symmetricalpalmtree.notesprout.extension.IExtensionStore
import com.symmetricalpalmtree.notesprout.extension.ILinkCatalog
import com.symmetricalpalmtree.notesprout.extension.LinkChoice

/**
 * Process-wide state shared by [LinkProviderService] (the host's held bind) and [LinkPickerActivity]
 * (the picker screen) — they live in the same process. It holds **only what the host lent for this
 * showing** (rule 25): the store and catalog binders from `beginPick`, the notebook the link lives
 * in, the payload to pre-populate for an Edit, and the slot the screen parks its [LinkChoice] in for
 * `takeResult`. `clear()` drops it all — both binders die with the bind anyway. **Nothing here is
 * ever written to disk by the extension itself**: the trail lives in the host store (rule 31).
 */
object PickSession {
    @Volatile var store: IExtensionStore? = null
    @Volatile var catalog: ILinkCatalog? = null
    @Volatile var currentNotebookId: String? = null
    @Volatile var editPayload: String? = null

    /** What the picker chose, drained once by `takeResult`. */
    @Volatile var result: LinkChoice? = null

    @Synchronized
    fun clear() {
        store = null
        catalog = null
        currentNotebookId = null
        editPayload = null
        result = null
    }
}
