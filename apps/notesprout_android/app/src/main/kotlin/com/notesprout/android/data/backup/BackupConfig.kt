package com.notesprout.android.data.backup

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class BackupConfig(
    val deviceId: String,
    val deviceFolderName: String,
    val localTreeUri: String? = null,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val localEnabled: Boolean = false,
    val driveTreeUri: String? = null,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val driveEnabled: Boolean = false,
    val driveAccountEmail: String? = null,
    val lastRunAt: Long? = null,
) {
    fun toJson(): String = codec.encodeToString(serializer(), this)

    companion object {
        private val codec = Json { ignoreUnknownKeys = true }

        fun fromJson(json: String): BackupConfig = codec.decodeFromString(serializer(), json)

        fun newDefault(deviceFolderName: String): BackupConfig = BackupConfig(
            deviceId = UUID.randomUUID().toString(),
            deviceFolderName = deviceFolderName,
        )
    }
}
