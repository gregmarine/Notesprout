package com.notesprout.android.data.backup

import com.notesprout.android.data.index.NotebookObject
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class NotebookObjectBackupTest {

    private val lenient = Json { ignoreUnknownKeys = true }

    @Test
    fun legacyJson_decodesWithBackupDefaults() {
        val legacyJson = """{"encrypted":false,"keyScope":null,"pageCount":3}"""
        val obj = lenient.decodeFromString<NotebookObject>(legacyJson)
        assertFalse(obj.excludeFromBackup)
        assertNull(obj.lastBackedUpLocal)
        assertNull(obj.lastBackedUpDrive)
    }

    @Test
    fun legacyJson_minimalEmpty_decodesWithBackupDefaults() {
        val legacyJson = """{}"""
        val obj = lenient.decodeFromString<NotebookObject>(legacyJson)
        assertFalse(obj.excludeFromBackup)
        assertNull(obj.lastBackedUpLocal)
        assertNull(obj.lastBackedUpDrive)
    }
}
