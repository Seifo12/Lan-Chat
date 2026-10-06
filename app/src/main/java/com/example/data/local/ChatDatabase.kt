package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * The schema version.
 *
 * Declared at the top level because the @Database annotation needs a compile-time
 * constant, and a companion constant is not usable from the annotation on its own
 * class. Keeping it here is what stops the declared version and the number tests
 * and tooling read from drifting apart.
 */
const val SCHEMA_VERSION = 14

@Database(
    entities = [
        ChatMessageEntity::class,
        ContactEntity::class,
        GroupEntity::class,
        DiscoveredPeerEntity::class,
        SeenIdEntity::class,
        PeerCounterEntity::class,
        GroupMemberEntity::class,
        GroupInviteEntity::class
    ],
    version = SCHEMA_VERSION,
    exportSchema = true
)
abstract class ChatDatabase : RoomDatabase() {

    abstract fun chatMessageDao(): ChatMessageDao
    abstract fun contactDao(): ContactDao

    abstract fun seenIdDao(): SeenIdDao

    abstract fun peerCounterDao(): PeerCounterDao
    abstract fun groupDao(): GroupDao
    abstract fun discoveredPeerDao(): DiscoveredPeerDao

    abstract fun groupMembershipDao(): GroupMembershipDao

    companion object {
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

        /**
         * Phase 1.3: persisted per-peer counters. Both directions survive a
         * restart, which is what stops a capture being replayed by closing the
         * app and stops a sender reissuing counters its peer has already seen.
         */
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `peer_counters` (" +
                        "`peerDeviceId` TEXT NOT NULL, " +
                        "`outgoingCounter` INTEGER NOT NULL, " +
                        "`highWaterMark` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`peerDeviceId`))"
                )
            }
        }

        /**
         * Phase 1.6: the identity pin.
         *
         * Existing installs are pinned to the key they are already using. Their
         * deviceId does not change, because conversationId is derived from it and
         * a new value would make every existing contact see a different person and
         * lose all history. Trust travels with the pin rather than the identifier.
         */
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE contacts ADD COLUMN pinnedPublicKey TEXT")
                db.execSQL("ALTER TABLE contacts ADD COLUMN hasKeyChanged INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "UPDATE contacts SET pinnedPublicKey = publicKeyBase64 " +
                        "WHERE publicKeyBase64 IS NOT NULL AND publicKeyBase64 != ''"
                )
            }
        }

        /** Phase 1.7b: when the user confirmed a contact by comparing the code. */
        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE contacts ADD COLUMN verifiedAt INTEGER")
            }
        }

        /**
         * Phase 1.8: signed membership.
         *
         * Every group row that exists now is marked legacy. None of them can
         * ever be given a signed member list: they have no roster, and their
         * createdBy column holds a display name rather than a device id, so
         * there is no key to attribute them to. Legacy means readable history
         * that can never become writable. Nothing is deleted, because a chat
         * history is not an attack surface worth destroying on upgrade.
         */
        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE groups ADD COLUMN creatorDeviceId TEXT")
                db.execSQL(
                    "ALTER TABLE groups ADD COLUMN memberListVersion INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL("ALTER TABLE groups ADD COLUMN isLegacy INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE groups SET isLegacy = 1")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `group_members` (" +
                        "`groupId` TEXT NOT NULL, " +
                        "`deviceId` TEXT NOT NULL, " +
                        "`publicKeyBase64` TEXT NOT NULL, " +
                        "`displayName` TEXT NOT NULL, " +
                        "`addedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`groupId`, `deviceId`))"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_group_members_deviceId` " +
                        "ON `group_members` (`deviceId`)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `group_invites` (" +
                        "`groupId` TEXT NOT NULL, " +
                        "`nonce` TEXT NOT NULL, " +
                        "`creatorId` TEXT NOT NULL, " +
                        "`groupName` TEXT NOT NULL, " +
                        "`memberListVersion` INTEGER NOT NULL, " +
                        "`expiry` INTEGER NOT NULL, " +
                        "`rawBytes` BLOB NOT NULL, " +
                        "`state` TEXT NOT NULL, " +
                        "`receivedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`groupId`, `nonce`))"
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
                MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9,
                MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12,
                MIGRATION_12_13, MIGRATION_13_14
            )
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}