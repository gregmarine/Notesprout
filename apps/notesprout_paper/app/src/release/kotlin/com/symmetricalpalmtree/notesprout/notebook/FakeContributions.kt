package com.symmetricalpalmtree.notesprout.notebook

import com.symmetricalpalmtree.notesprout.extension.EditSpec

/** Release build: no fake contributions exist (arc 4 / H2 debug scaffolding). */
object FakeContributions {
    fun contributions(): List<Contribution> = emptyList()
    fun activeActionIds(obj: PageObject): Set<String> = emptySet()
    fun editSpec(obj: PageObject): EditSpec? = null
    fun onLeafTapped(providerKey: String?, action: ToolbarAction) = Unit
    fun onEditSaved(obj: PageObject, text: String) = Unit
}
