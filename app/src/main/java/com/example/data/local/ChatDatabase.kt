package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ChatMessageEntity::class,
        ContactEntity::class,
        GroupEntity::class,
        DiscoveredPeerEntity::class,
        SeenIdEntity::class
    ],
    version = 10,
    exportSchema = true
)
abstract class ChatDatabase : RoomDatabase() {

    abstract fun chatMessageDao(): ChatMessageDao
    abstract fun contactDao(): ContactDao

    abstract fun seenIdDao(): SeenIdDao
    abstract fun groupDao(): GroupDao
    abstract fun discoveredPeerDao(): DiscoveredPeerDao

    companion object {
        /**
         * The single source of truth for the schema version, so tests and tooling
         * never hardcode a second copy of the number that @Database declares.
         */
        const val SCHEMA_VERSION = 10

        @Volatile
        private var INSTANCE: ChatDatabase? = null

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN meshHops INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS discovered_peers (
                        deviceId TEXT NOT NULL,
                        displayName TEXT NOT NULL,
                        endpointId TEXT NOT NULL DEFAULT '',
                        ipAddress TEXT,
                        publicKeyBase64 TEXT,
                        avatarColorIndex INTEGER NOT NULL DEFAULT 0,
                        isDeveloper INTEGER NOT NULL DEFAULT 0,
                        appVersionCode INTEGER NOT NULL DEFAULT 1,
                        transportIsLan INTEGER NOT NULL DEFAULT 0,
                        lastSeen INTEGER NOT NULL,
                        PRIMARY KEY(deviceId)
                    )
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN receivedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE messages SET receivedAt = timestamp")
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE contacts ADD COLUMN meshEndpointId TEXT")
                // Old rows kept the Nearby endpoint embedded in the address, which
                // is why they had no LAN route. Lift it out so MESH reachability
                // survives the move to a dedicated column.
                db.execSQL(
                    "UPDATE contacts SET meshEndpointId = " +
                        "substr(ipAddress, 5) WHERE ipAddress LIKE 'p2p-%'"
                )
                db.execSQL(
                    "UPDATE contacts SET meshEndpointId = " +
                        "substr(ipAddress, 4) WHERE meshEndpointId IS NULL AND ipAddress LIKE 'qr-%'"
                )
            }
        }

        /**
         * Phase 1.5: durable replay records. A new table rather than a column,
         * because a record has to outlive the conversation it came from. No
         * foreign key, so deleting a chat cannot clear the evidence.
         */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `seen_ids` (" +
                        "`senderId` TEXT NOT NULL, " +
                        "`messageId` TEXT NOT NULL, " +
                        "`receivedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`senderId`, `messageId`))"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_seen_ids_receivedAt` " +
                        "ON `seen_ids` (`receivedAt`)"
                )
            }
        }

        fun getDatabase(context: Context): ChatDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    ChatDatabase::class.java,
                    "lan_chat_database"
                ).addMigrations(
                MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10
            )
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}