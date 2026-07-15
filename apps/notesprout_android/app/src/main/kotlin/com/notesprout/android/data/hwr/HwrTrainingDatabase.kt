package com.notesprout.android.data.hwr

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.notesprout.android.crypto.GlobalKey
import com.notesprout.android.crypto.KeyMaterial
import com.notesprout.android.crypto.SoilCrypto
import com.notesprout.android.crypto.SoilFileKind
import com.notesprout.android.crypto.SoilMigrator
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * SQLCipher-encrypted Room DB of handwriting training pairs at `filesDir/hwr/training.db`.
 *
 * Encrypted at rest under the global key (Phase 1b) via the derive-once raw-key cache. Still NOT the
 * global index and NOT inside any `.soil` — pairs cross notebooks and must never enter
 * `notesprout.db` (search-leak invariant). Capture gating (which notebooks contribute pairs) is
 * unchanged and independent of this file-level encryption.
 *
 * [dao] is always invoked from Dispatchers.IO (see [com.notesprout.android.recognition.personal.TrainingPairRepository]),
 * so the one-time key derivation / plaintext→encrypted migration runs off the UI thread.
 */
@Database(entities = [TrainingPairEntity::class], version = 1, exportSchema = false)
abstract class HwrTrainingDatabase : RoomDatabase() {

    abstract fun trainingPairDao(): TrainingPairDao

    companion object {
        @Volatile
        private var instance: HwrTrainingDatabase? = null

        fun dao(context: Context): TrainingPairDao {
            instance?.let { return it.trainingPairDao() }
            synchronized(this) {
                instance?.let { return it.trainingPairDao() }
                val app = context.applicationContext
                val dbFile = File(app.filesDir, "hwr/training.db")
                dbFile.parentFile?.mkdirs()
                val pass = GlobalKey.ensure(app)

                val builder = Room.databaseBuilder(app, HwrTrainingDatabase::class.java, dbFile.absolutePath)
                when (SoilCrypto.probe(dbFile)) {
                    SoilFileKind.Plaintext -> {
                        // Existing plaintext store from a pre-encryption build — migrate in place.
                        runBlocking { SoilMigrator.encryptInPlace(dbFile, pass) }
                        val key = KeyMaterial.rawKeyGlobal(app, KeyMaterial.TRAINING_FILE_ID, dbFile, pass)
                        builder.openHelperFactory(SoilCrypto.roomFactoryRawKey(key))
                    }
                    SoilFileKind.Encrypted -> {
                        val key = KeyMaterial.rawKeyGlobal(app, KeyMaterial.TRAINING_FILE_ID, dbFile, pass)
                        builder.openHelperFactory(SoilCrypto.roomFactoryRawKey(key))
                    }
                    SoilFileKind.Invalid -> {
                        // Missing/empty → Room creates it encrypted under the passphrase. The raw key
                        // gets cached on the next process (Encrypted branch) once the file has a salt.
                        builder.openHelperFactory(SoilCrypto.roomFactory(pass))
                    }
                }
                val db = builder.build()
                instance = db
                return db.trainingPairDao()
            }
        }
    }
}
