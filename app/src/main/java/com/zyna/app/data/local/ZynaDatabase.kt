package com.zyna.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import net.zetetic.database.sqlcipher.SQLiteConnection
import net.zetetic.database.sqlcipher.SQLiteDatabaseHook
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(
    entities = [
        CachedRoomEntity::class,
        CachedTimelineMessageEntity::class,
        OutgoingEnvelopeEntity::class
    ],
    version = 3,
    exportSchema = true
)
abstract class ZynaDatabase : RoomDatabase() {
    abstract fun cachedRoomDao(): CachedRoomDao

    abstract fun cachedTimelineMessageDao(): CachedTimelineMessageDao

    abstract fun outgoingEnvelopeDao(): OutgoingEnvelopeDao

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
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
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

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    ALTER TABLE timeline_messages
                    ADD COLUMN deliveryState TEXT NOT NULL DEFAULT 'SENT'
                    """.trimIndent()
                )
                db.execSQL("DELETE FROM timeline_messages WHERE id LIKE 'local:%'")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS outgoing_envelopes (
                        userId TEXT NOT NULL,
                        roomId TEXT NOT NULL,
                        id TEXT NOT NULL,
                        kind TEXT NOT NULL,
                        transportState TEXT NOT NULL,
                        transactionId TEXT NOT NULL,
                        eventId TEXT,
                        body TEXT NOT NULL,
                        createdAtMillis INTEGER NOT NULL,
                        updatedAtMillis INTEGER NOT NULL,
                        failureMessage TEXT,
                        PRIMARY KEY(userId, roomId, id)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS index_outgoing_envelopes_userId_roomId_transportState
                    ON outgoing_envelopes(userId, roomId, transportState)
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE UNIQUE INDEX IF NOT EXISTS index_outgoing_envelopes_userId_roomId_transactionId
                    ON outgoing_envelopes(userId, roomId, transactionId)
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS index_outgoing_envelopes_userId_roomId_eventId
                    ON outgoing_envelopes(userId, roomId, eventId)
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE rooms ADD COLUMN lastMessageText TEXT")
                db.execSQL("ALTER TABLE rooms ADD COLUMN lastMessageSenderName TEXT")
                db.execSQL("ALTER TABLE rooms ADD COLUMN lastMessageAtMillis INTEGER")
            }
        }
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
