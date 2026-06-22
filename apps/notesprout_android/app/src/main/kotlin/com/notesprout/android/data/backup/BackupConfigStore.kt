package com.notesprout.android.data.backup

import com.notesprout.android.data.index.BACKUP_CONFIG_ID
import com.notesprout.android.data.index.ObjectDao
import com.notesprout.android.data.index.ObjectEntity
import com.notesprout.android.data.index.ObjectType

object BackupConfigStore {

    suspend fun read(dao: ObjectDao): BackupConfig? {
        val entity = dao.getById(BACKUP_CONFIG_ID) ?: return null
        if (entity.deletedAt != null) return null
        return try { BackupConfig.fromJson(entity.data) } catch (_: Exception) { null }
    }

    suspend fun write(dao: ObjectDao, config: BackupConfig) {
        val now = System.currentTimeMillis()
        val entity = ObjectEntity(
            id = BACKUP_CONFIG_ID,
            type = ObjectType.BACKUP_CONFIG,
            name = "backup_config",
            parentId = null,
            createdAt = now,
            updatedAt = now,
            deletedAt = null,
            data = config.toJson(),
        )
        val existing = dao.getById(BACKUP_CONFIG_ID)
        if (existing != null) dao.update(entity) else dao.insert(entity)
    }

    suspend fun ensure(dao: ObjectDao, defaultDeviceFolderName: String): BackupConfig {
        val existing = read(dao)
        if (existing != null) return existing
        val default = BackupConfig.newDefault(defaultDeviceFolderName)
        write(dao, default)
        return default
    }
}
