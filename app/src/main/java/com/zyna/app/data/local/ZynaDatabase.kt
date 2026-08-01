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
        OutgoingEnvelopeEntity::class,
        PendingReactionEntity::class,
        MatrixRtcCallEntity::class,
        MatrixRtcCallMembershipEntity::class,
        CachedSpaceListSnapshotEntity::class,
        CachedSpaceListEntryEntity::class
    ],
    version = 26,
    exportSchema = true
)
abstract class ZynaDatabase : RoomDatabase() {
    abstract fun cachedRoomDao(): CachedRoomDao

    abstract fun cachedTimelineMessageDao(): CachedTimelineMessageDao

    abstract fun outgoingEnvelopeDao(): OutgoingEnvelopeDao

    abstract fun pendingReactionDao(): PendingReactionDao

    abstract fun matrixRtcCallHistoryDao(): MatrixRtcCallHistoryDao

    abstract fun cachedSpaceDao(): CachedSpaceDao

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
                .addMigrations(
                    MIGRATION_1_2,
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                    MIGRATION_6_7,
                    MIGRATION_7_8,
                    MIGRATION_8_9,
                    MIGRATION_9_10,
                    MIGRATION_10_11,
                    MIGRATION_11_12,
                    MIGRATION_12_13,
                    MIGRATION_13_14,
                    MIGRATION_14_15,
                    MIGRATION_15_16,
                    MIGRATION_16_17,
                    MIGRATION_17_18,
                    MIGRATION_18_19,
                    MIGRATION_19_20,
                    MIGRATION_20_21,
                    MIGRATION_21_22,
                    MIGRATION_22_23,
                    MIGRATION_23_24,
                    MIGRATION_24_25,
                    MIGRATION_25_26
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

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE rooms ADD COLUMN lastOwnMessageStatus TEXT")
                db.execSQL("ALTER TABLE rooms ADD COLUMN unreadCount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE rooms ADD COLUMN unreadMentionCount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE rooms ADD COLUMN isMarkedUnread INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE timeline_messages ADD COLUMN eventId TEXT")
                db.execSQL("ALTER TABLE timeline_messages ADD COLUMN transactionId TEXT")
                db.execSQL(
                    """
                    ALTER TABLE timeline_messages
                    ADD COLUMN contentType TEXT NOT NULL DEFAULT 'TEXT'
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    UPDATE timeline_messages
                    SET eventId = CASE
                            WHEN id LIKE '$%' THEN id
                            ELSE NULL
                        END,
                        transactionId = CASE
                            WHEN id NOT LIKE '$%' THEN id
                            ELSE NULL
                        END
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS index_timeline_messages_userId_roomId_eventId
                    ON timeline_messages(userId, roomId, eventId)
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS index_timeline_messages_userId_roomId_transactionId
                    ON timeline_messages(userId, roomId, transactionId)
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE outgoing_envelopes ADD COLUMN targetEventId TEXT")
                db.execSQL("ALTER TABLE outgoing_envelopes ADD COLUMN targetTransactionId TEXT")
                db.execSQL("ALTER TABLE outgoing_envelopes ADD COLUMN targetBody TEXT")
                db.execSQL("ALTER TABLE outgoing_envelopes ADD COLUMN targetContentType TEXT")
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE timeline_messages ADD COLUMN replyEventId TEXT")
                db.execSQL("ALTER TABLE timeline_messages ADD COLUMN replySenderId TEXT")
                db.execSQL("ALTER TABLE timeline_messages ADD COLUMN replySenderDisplayName TEXT")
                db.execSQL("ALTER TABLE timeline_messages ADD COLUMN replyBody TEXT")
                db.execSQL("ALTER TABLE outgoing_envelopes ADD COLUMN replyEventId TEXT")
                db.execSQL("ALTER TABLE outgoing_envelopes ADD COLUMN replySenderId TEXT")
                db.execSQL("ALTER TABLE outgoing_envelopes ADD COLUMN replySenderDisplayName TEXT")
                db.execSQL("ALTER TABLE outgoing_envelopes ADD COLUMN replyBody TEXT")
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE timeline_messages ADD COLUMN isEdited INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE timeline_messages ADD COLUMN isEditPending INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE timeline_messages ADD COLUMN isEditFailed INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE timeline_messages ADD COLUMN latestEditEventId TEXT")
                db.execSQL("ALTER TABLE timeline_messages ADD COLUMN editTransactionId TEXT")
                db.execSQL("ALTER TABLE timeline_messages ADD COLUMN pendingEditBody TEXT")
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE timeline_messages ADD COLUMN senderDisplayName TEXT")
                db.execSQL("ALTER TABLE timeline_messages ADD COLUMN forwardedFrom TEXT")
                db.execSQL("ALTER TABLE outgoing_envelopes ADD COLUMN forwardedFrom TEXT")
            }
        }

        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE timeline_messages ADD COLUMN imageSourceJson TEXT")
                db.execSQL("ALTER TABLE timeline_messages ADD COLUMN imageThumbnailSourceJson TEXT")
                db.execSQL("ALTER TABLE timeline_messages ADD COLUMN imageWidth INTEGER")
                db.execSQL("ALTER TABLE timeline_messages ADD COLUMN imageHeight INTEGER")
                db.execSQL("ALTER TABLE timeline_messages ADD COLUMN imageCaption TEXT")
                db.execSQL("ALTER TABLE timeline_messages ADD COLUMN imageMimeType TEXT")
                db.execSQL("ALTER TABLE timeline_messages ADD COLUMN imageBlurhash TEXT")
            }
        }

        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE timeline_messages ADD COLUMN zynaAttributesJson TEXT")
            }
        }

        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE outgoing_envelopes ADD COLUMN imageLocalPath TEXT")
                db.execSQL("ALTER TABLE outgoing_envelopes ADD COLUMN imageMimeType TEXT")
                db.execSQL("ALTER TABLE outgoing_envelopes ADD COLUMN imageWidth INTEGER")
                db.execSQL("ALTER TABLE outgoing_envelopes ADD COLUMN imageHeight INTEGER")
                db.execSQL("ALTER TABLE outgoing_envelopes ADD COLUMN imageSizeBytes INTEGER")
                db.execSQL("ALTER TABLE outgoing_envelopes ADD COLUMN imageCaption TEXT")
                db.execSQL("ALTER TABLE outgoing_envelopes ADD COLUMN zynaAttributesJson TEXT")
            }
        }

        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE outgoing_envelopes ADD COLUMN imageUploadedJson TEXT")
                db.execSQL("ALTER TABLE outgoing_envelopes ADD COLUMN imageUploadedAtMillis INTEGER")
            }
        }

        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE outgoing_envelopes ADD COLUMN imageSourceJson TEXT")
                db.execSQL("ALTER TABLE outgoing_envelopes ADD COLUMN imageThumbnailSourceJson TEXT")
                db.execSQL("ALTER TABLE outgoing_envelopes ADD COLUMN imageBlurhash TEXT")
            }
        }

        private val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE outgoing_envelopes ADD COLUMN imageThumbnailLocalPath TEXT")
                db.execSQL("ALTER TABLE outgoing_envelopes ADD COLUMN imageThumbnailMimeType TEXT")
                db.execSQL("ALTER TABLE outgoing_envelopes ADD COLUMN imageThumbnailWidth INTEGER")
                db.execSQL("ALTER TABLE outgoing_envelopes ADD COLUMN imageThumbnailHeight INTEGER")
                db.execSQL("ALTER TABLE outgoing_envelopes ADD COLUMN imageThumbnailSizeBytes INTEGER")
            }
        }

        private val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE rooms ADD COLUMN directUserId TEXT")
            }
        }

        private val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE timeline_messages ADD COLUMN audioSourceJson TEXT")
                db.execSQL("ALTER TABLE timeline_messages ADD COLUMN audioFilename TEXT")
                db.execSQL("ALTER TABLE timeline_messages ADD COLUMN audioCaption TEXT")
                db.execSQL("ALTER TABLE timeline_messages ADD COLUMN audioMimeType TEXT")
                db.execSQL("ALTER TABLE timeline_messages ADD COLUMN audioSizeBytes INTEGER")
                db.execSQL("ALTER TABLE timeline_messages ADD COLUMN audioDurationMillis INTEGER")
                db.execSQL("ALTER TABLE timeline_messages ADD COLUMN audioWaveform TEXT")
                db.execSQL(
                    """
                    ALTER TABLE timeline_messages
                    ADD COLUMN audioIsVoice INTEGER NOT NULL DEFAULT 0
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE outgoing_envelopes ADD COLUMN voiceLocalPath TEXT")
                db.execSQL("ALTER TABLE outgoing_envelopes ADD COLUMN voiceMimeType TEXT")
                db.execSQL("ALTER TABLE outgoing_envelopes ADD COLUMN voiceSizeBytes INTEGER")
                db.execSQL("ALTER TABLE outgoing_envelopes ADD COLUMN voiceDurationMillis INTEGER")
                db.execSQL("ALTER TABLE outgoing_envelopes ADD COLUMN voiceWaveform TEXT")
                db.execSQL("ALTER TABLE outgoing_envelopes ADD COLUMN voiceUploadedJson TEXT")
                db.execSQL("ALTER TABLE outgoing_envelopes ADD COLUMN voiceUploadedAtMillis INTEGER")
            }
        }

        private val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    ALTER TABLE timeline_messages
                    ADD COLUMN reactionsJson TEXT NOT NULL DEFAULT '[]'
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS pending_reactions (
                        userId TEXT NOT NULL,
                        roomId TEXT NOT NULL,
                        id TEXT NOT NULL,
                        targetEventId TEXT NOT NULL,
                        reactionKey TEXT NOT NULL,
                        state TEXT NOT NULL,
                        transactionId TEXT,
                        reactionEventId TEXT,
                        redactionTransactionId TEXT,
                        redactionEventId TEXT,
                        createdAtMillis INTEGER NOT NULL,
                        updatedAtMillis INTEGER NOT NULL,
                        failureMessage TEXT,
                        lastAttemptAtMillis INTEGER,
                        attemptCount INTEGER NOT NULL,
                        PRIMARY KEY(userId, roomId, id)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS index_pending_reactions_userId_roomId_targetEventId_reactionKey
                    ON pending_reactions(userId, roomId, targetEventId, reactionKey)
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS index_pending_reactions_userId_state_updatedAtMillis
                    ON pending_reactions(userId, state, updatedAtMillis)
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS index_pending_reactions_userId_roomId_reactionEventId
                    ON pending_reactions(userId, roomId, reactionEventId)
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS matrix_rtc_calls (
                        userId TEXT NOT NULL,
                        eventId TEXT NOT NULL,
                        roomId TEXT NOT NULL,
                        parentEventId TEXT,
                        senderId TEXT NOT NULL,
                        senderDisplayName TEXT,
                        isOutgoing INTEGER NOT NULL,
                        timestampMillis INTEGER NOT NULL,
                        notificationType TEXT NOT NULL,
                        callIntent TEXT,
                        expiresAtMillis INTEGER,
                        declinedByJson TEXT NOT NULL,
                        isDirect INTEGER NOT NULL,
                        hasOwnJoin INTEGER NOT NULL,
                        hasRemoteJoin INTEGER NOT NULL,
                        hasOwnLeave INTEGER NOT NULL,
                        hasRemoteLeave INTEGER NOT NULL,
                        lastMembershipEventTimestampMillis INTEGER,
                        lastOwnLeaveTimestampMillis INTEGER,
                        lastRemoteLeaveTimestampMillis INTEGER,
                        outcome TEXT NOT NULL,
                        updatedAtMillis INTEGER NOT NULL,
                        PRIMARY KEY(userId, eventId)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS index_matrix_rtc_calls_userId_timestampMillis
                    ON matrix_rtc_calls(userId, timestampMillis)
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS index_matrix_rtc_calls_userId_roomId_timestampMillis
                    ON matrix_rtc_calls(userId, roomId, timestampMillis)
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS index_matrix_rtc_calls_userId_outcome_expiresAtMillis
                    ON matrix_rtc_calls(userId, outcome, expiresAtMillis)
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS matrix_rtc_call_memberships (
                        userId TEXT NOT NULL,
                        eventId TEXT NOT NULL,
                        roomId TEXT NOT NULL,
                        eventType TEXT NOT NULL,
                        stateKey TEXT,
                        senderId TEXT NOT NULL,
                        timestampMillis INTEGER NOT NULL,
                        isLeave INTEGER NOT NULL,
                        memberUserId TEXT,
                        deviceId TEXT,
                        memberId TEXT,
                        callIntent TEXT,
                        expiresAtMillis INTEGER,
                        updatedAtMillis INTEGER NOT NULL,
                        PRIMARY KEY(userId, eventId)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS index_matrix_rtc_call_memberships_userId_roomId_timestampMillis
                    ON matrix_rtc_call_memberships(userId, roomId, timestampMillis)
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS index_matrix_rtc_call_memberships_userId_roomId_stateKey
                    ON matrix_rtc_call_memberships(userId, roomId, stateKey)
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS index_matrix_rtc_call_memberships_userId_memberUserId_timestampMillis
                    ON matrix_rtc_call_memberships(userId, memberUserId, timestampMillis)
                    """.trimIndent()
                )
            }
        }

        private val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE timeline_messages ADD COLUMN timelineDetailsJson TEXT")
            }
        }

        private val MIGRATION_21_22 = object : Migration(21, 22) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE rooms ADD COLUMN isSpace INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE rooms ADD COLUMN detailsTopic TEXT")
                db.execSQL("ALTER TABLE rooms ADD COLUMN detailsJoinedMemberCount INTEGER")
                db.execSQL("ALTER TABLE rooms ADD COLUMN detailsEncryption TEXT")
                db.execSQL("ALTER TABLE rooms ADD COLUMN detailsAccess TEXT")
                db.execSQL("ALTER TABLE rooms ADD COLUMN detailsHistoryVisibility TEXT")
                db.execSQL("ALTER TABLE rooms ADD COLUMN detailsPinnedEventCount INTEGER")
                db.execSQL("ALTER TABLE rooms ADD COLUMN detailsCanonicalAlias TEXT")
                db.execSQL("ALTER TABLE rooms ADD COLUMN detailsUpdatedAtMillis INTEGER")
            }
        }

        private val MIGRATION_22_23 = object : Migration(22, 23) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE rooms ADD COLUMN detailsCanInviteMembers INTEGER")
            }
        }

        private val MIGRATION_23_24 = object : Migration(23, 24) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE rooms ADD COLUMN detailsCanChangeName INTEGER")
                db.execSQL("ALTER TABLE rooms ADD COLUMN detailsCanChangeAvatar INTEGER")
            }
        }

        private val MIGRATION_24_25 = object : Migration(24, 25) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE rooms ADD COLUMN detailsRoomVersion TEXT")
                db.execSQL("ALTER TABLE rooms ADD COLUMN detailsCreatorSemantics TEXT")
            }
        }

        private val MIGRATION_25_26 = object : Migration(25, 26) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS space_list_snapshots (
                        userId TEXT NOT NULL,
                        listId TEXT NOT NULL,
                        spaceRoomId TEXT,
                        spaceDisplayName TEXT,
                        spaceAvatarUrl TEXT,
                        spaceTopic TEXT,
                        spaceMembership TEXT,
                        spaceJoinedMemberCount INTEGER,
                        spaceChildrenCount INTEGER,
                        spaceCanonicalAlias TEXT,
                        spaceJoinRule TEXT,
                        spaceWorldReadable INTEGER,
                        spaceGuestCanJoin INTEGER,
                        spaceIsDirect INTEGER,
                        spaceIsDm INTEGER,
                        spaceViaJson TEXT,
                        isKnown INTEGER NOT NULL,
                        endReached INTEGER NOT NULL,
                        updatedAtMillis INTEGER NOT NULL,
                        PRIMARY KEY(userId, listId)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS space_list_entries (
                        userId TEXT NOT NULL,
                        listId TEXT NOT NULL,
                        roomId TEXT NOT NULL,
                        position INTEGER NOT NULL,
                        displayName TEXT NOT NULL,
                        avatarUrl TEXT,
                        topic TEXT,
                        kind TEXT NOT NULL,
                        membership TEXT NOT NULL,
                        joinedMemberCount INTEGER NOT NULL,
                        childrenCount INTEGER NOT NULL,
                        canonicalAlias TEXT,
                        joinRule TEXT NOT NULL,
                        worldReadable INTEGER,
                        guestCanJoin INTEGER NOT NULL,
                        isDirect INTEGER,
                        isDm INTEGER,
                        viaJson TEXT NOT NULL,
                        PRIMARY KEY(userId, listId, roomId)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS index_space_list_entries_userId_listId_position
                    ON space_list_entries(userId, listId, position)
                    """.trimIndent()
                )
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
