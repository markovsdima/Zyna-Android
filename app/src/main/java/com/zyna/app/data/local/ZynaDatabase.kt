package com.zyna.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import net.zetetic.database.sqlcipher.SQLiteConnection
import net.zetetic.database.sqlcipher.SQLiteDatabaseHook
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(
    entities = [
        CachedRoomEntity::class,
        CachedTimelineMessageEntity::class
    ],
    version = 1,
    exportSchema = true
)
abstract class ZynaDatabase : RoomDatabase() {
    abstract fun cachedRoomDao(): CachedRoomDao

    abstract fun cachedTimelineMessageDao(): CachedTimelineMessageDao

    companion object {
        fun create(context: Context, passphraseStore: LocalDatabasePassphraseStore): ZynaDatabase {
            deleteLegacyDatabases(context)
            loadSqlCipher()
            val passphrase = passphraseStore.loadOrCreatePassphrase()
            if (passphrase.didReset) {
                deleteDatabase(context)
            }
            return Room.databaseBuilder(
                context,
                ZynaDatabase::class.java,
                DATABASE_NAME
            )
                .openHelperFactory(
                    SupportOpenHelperFactory(
                        passphrase.bytes,
                        SqlCipherValidationHook,
                        false
                    )
                )
                .build()
        }

        private fun deleteLegacyDatabases(context: Context) {
            context.deleteDatabase(LEGACY_PLAINTEXT_DATABASE_NAME)
            context.deleteDatabase(LEGACY_UNSCOPED_DATABASE_NAME)
        }

        private fun deleteDatabase(context: Context) {
            context.deleteDatabase(DATABASE_NAME)
        }

        @Synchronized
        private fun loadSqlCipher() {
            if (!isSqlCipherLoaded) {
                System.loadLibrary("sqlcipher")
                isSqlCipherLoaded = true
            }
        }

        private var isSqlCipherLoaded = false
        private const val DATABASE_NAME = "zyna-secure-cache.db"
        private const val LEGACY_PLAINTEXT_DATABASE_NAME = "zyna.db"
        private const val LEGACY_UNSCOPED_DATABASE_NAME = "zyna-secure.db"
    }
}

private object SqlCipherValidationHook : SQLiteDatabaseHook {
    override fun preKey(connection: SQLiteConnection) = Unit

    override fun postKey(connection: SQLiteConnection) {
        val cipherVersion = connection.executeForString(
            "PRAGMA cipher_version",
            emptyArray<Any>(),
            null
        )
        check(cipherVersion.isNotBlank()) { "SQLCipher is unavailable" }
    }
}
