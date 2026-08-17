package com.symmetricalpalmtree.notesprout.ext.naming

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.symmetricalpalmtree.notesprout.extension.HostCallerCheck
import com.symmetricalpalmtree.notesprout.extension.IExtensionStore
import com.symmetricalpalmtree.notesprout.extension.INotebookNamer
import com.symmetricalpalmtree.notesprout.extension.SchemeField

/**
 * The NOTEBOOK_NAMER extension point. Bound by the Notesprout Paper core; never launched by a user
 * (this package declares no Activity). Every method first proves the caller is the host, then works
 * against the store the host handed in — key `folder:<folderId>` → UTF-8 scheme text. Holds no
 * mutable state; AIDL methods run on Binder threads.
 *
 * Any store failure is rethrown as `IllegalStateException` (one of the exceptions Binder carries
 * across intact) so the host sees a clean failure instead of a dead extension process.
 */
class NotebookNamerService : Service() {

    private val binder = object : INotebookNamer.Stub() {

        override fun describeField(): SchemeField {
            enforce()
            return SchemeField(getString(R.string.field_label), getString(R.string.field_hint), getString(R.string.field_help))
        }

        override fun currentScheme(store: IExtensionStore, folderId: String): String? {
            enforce()
            return read(store, folderId)
        }

        override fun validateScheme(scheme: String): String? {
            enforce()
            val trimmed = scheme.trim()
            if (trimmed.isEmpty()) return null   // empty = no scheme, always acceptable
            return SchemeEngine.validate(trimmed)?.let(::errorText)
        }

        override fun saveScheme(store: IExtensionStore, folderId: String, scheme: String) {
            enforce()
            val trimmed = scheme.trim()
            val key = keyFor(folderId)
            if (trimmed.isEmpty()) {
                storeCall { store.delete(key) }
                return
            }
            SchemeEngine.validate(trimmed)?.let { throw IllegalArgumentException(errorText(it)) }
            storeCall { store.put(key, trimmed.toByteArray(Charsets.UTF_8)) }
        }

        override fun defaultName(store: IExtensionStore, folderId: String, siblingNames: List<String>?): String? {
            enforce()
            val scheme = read(store, folderId) ?: return null
            return try {
                SchemeEngine.expand(scheme, System.currentTimeMillis(), siblingNames?.filterNotNull() ?: emptyList())
            } catch (e: SchemeEngine.SchemeException) {
                null   // a stored scheme this version can't parse → the host's default
            }
        }
    }

    private fun enforce() = HostCallerCheck.enforce(this, BuildConfig.HOST_PACKAGE)

    private fun keyFor(folderId: String): String {
        require(folderId.isNotBlank()) { "folderId is blank" }
        return "folder:$folderId"
    }

    private fun read(store: IExtensionStore, folderId: String): String? {
        val bytes = storeCall { store.get(keyFor(folderId)) } ?: return null
        return String(bytes, Charsets.UTF_8)
    }

    /** Runs a store call; any failure becomes `IllegalStateException` (carried across Binder). */
    private inline fun <T> storeCall(block: () -> T): T =
        try {
            block()
        } catch (e: Exception) {
            throw IllegalStateException(getString(R.string.err_store), e)
        }

    private fun errorText(e: SchemeEngine.SchemeException): String = when (e.error) {
        SchemeEngine.Error.UNKNOWN_TOKEN -> getString(R.string.err_unknown_token, e.detail)
        SchemeEngine.Error.UNCLOSED_BRACE -> getString(R.string.err_unclosed)
        SchemeEngine.Error.COUNTER_TWICE -> getString(R.string.err_counter_twice)
        SchemeEngine.Error.ILLEGAL_CHAR -> getString(R.string.err_illegal_char)
        SchemeEngine.Error.EMPTY -> getString(R.string.err_empty)
        SchemeEngine.Error.TOO_LONG -> getString(R.string.err_too_long)
    }

    override fun onBind(intent: Intent?): IBinder = binder
}
