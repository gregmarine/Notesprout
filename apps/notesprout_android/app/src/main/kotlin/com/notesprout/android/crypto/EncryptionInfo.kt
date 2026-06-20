package com.notesprout.android.crypto

data class EncryptionInfo(val encrypted: Boolean, val keyScope: KeyScope?) {
    companion object {
        val NONE = EncryptionInfo(false, null)
    }
}
