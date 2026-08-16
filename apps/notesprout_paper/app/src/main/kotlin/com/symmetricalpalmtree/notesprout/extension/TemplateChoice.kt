package com.symmetricalpalmtree.notesprout.extension

/** A template the user picked on the New-notebook screen: which provider, which id, its display name. */
data class TemplateChoice(val provider: ProviderRef, val id: String, val name: String) {
    /** `"<extension package>:<template id>"` — written to the index `templateKind` and the template row's `text`. */
    val identity: String get() = ExtensionContract.templateIdentity(provider.packageName, id)
}
