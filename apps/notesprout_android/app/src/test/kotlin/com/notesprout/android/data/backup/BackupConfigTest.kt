package com.notesprout.android.data.backup

import org.junit.Assert.*
import org.junit.Test

class BackupConfigTest {

    @Test
    fun roundTrip() {
        val config = BackupConfig(
            deviceId = "test-device-id",
            deviceFolderName = "BOOX-Go-103-abcd1234",
            localTreeUri = "content://local/tree/uri",
            localEnabled = true,
            driveTreeUri = null,
            driveEnabled = false,
            lastRunAt = 1_700_000_000_000L,
        )
        val json = config.toJson()
        val decoded = BackupConfig.fromJson(json)
        assertEquals(config, decoded)
    }

    @Test
    fun defaultsPresent_whenOptionalFieldsOmitted() {
        val minimalJson = """{"deviceId":"abc","deviceFolderName":"MyDevice"}"""
        val config = BackupConfig.fromJson(minimalJson)
        assertFalse(config.localEnabled)
        assertFalse(config.driveEnabled)
        assertNull(config.localTreeUri)
        assertNull(config.driveTreeUri)
        assertNull(config.lastRunAt)
    }

    @Test
    fun newDefault_generatesNonBlankDeviceId() {
        val config = BackupConfig.newDefault("TestDevice-abc123")
        assertTrue(config.deviceId.isNotBlank())
        assertEquals("TestDevice-abc123", config.deviceFolderName)
        assertFalse(config.localEnabled)
        assertFalse(config.driveEnabled)
    }

    @Test
    fun newDefault_deviceIdIsUnique() {
        val a = BackupConfig.newDefault("Device")
        val b = BackupConfig.newDefault("Device")
        assertNotEquals(a.deviceId, b.deviceId)
    }
}
