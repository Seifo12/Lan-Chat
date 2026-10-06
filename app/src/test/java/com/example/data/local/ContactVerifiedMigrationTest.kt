package com.example.data.local

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.security.ContactTrust
import com.example.data.security.TrustState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 1.7b: the version 12 to 13 migration, which adds `contacts.verifiedAt`.
 *
 * The property worth proving is the default. Every row that existed before this
 * migration must come back unverified, because unverified is the honest state for
 * a key that 1.6 pinned by backfill without anyone ever comparing a safety code.
 * A migration that stamped a timestamp here would quietly claim the user had
 * confirmed every contact they already had, which is exactly the "believe
 * whatever arrived" failure the pin exists to prevent.
 *
 * The other half is that `verifiedAt` has to be readable by the state machine that
 * 1.7b adds, or the column is inert storage.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ContactVerifiedMigrationTest {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        ChatDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val dbName = "phase17b-verified.db"

    private val knownKey = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEknown0000000000000000000000000000000000="
    private val otherKey = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEother0000000000000000000000000000000000="

    @After
    fun tearDown() {
        context.deleteDatabase(dbName)
    }

    private fun insertV12Contact(
        deviceId: String,
        pinned: String?,
        observed: String? = pinned,
        hasKeyChanged: Boolean = false,
    ) {
        val db = helper.createDatabase(dbName, 12)
        try {
            db.execSQL(
                "INSERT INTO contacts (deviceId, displayName, ipAddress, tcpPort, " +
                    "avatarColorIndex, lastSeen, isOnline, isDeveloper, " +
                    "appVersionCode, appVersionName, isMeshPeer, publicKeyBase64, " +
                    "pinnedPublicKey, hasKeyChanged) " +
                    "VALUES (?, 'Peer', '10.0.0.5', 9999, 0, 1000, 1, 0, 2, '2.0', 0, ?, ?, ?)",
                arrayOf<Any?>(deviceId, observed, pinned, if (hasKeyChanged) 1 else 0)
            )
        } finally {
            db.close()
        }
    }

    private fun migrateToCurrent(): ChatDatabase =
        androidx.room.Room.databaseBuilder(context, ChatDatabase::class.java, dbName).build()

    private fun runToCurrent() {
        helper.runMigrationsAndValidate(
            dbName, SCHEMA_VERSION, true,
            ChatDatabase.MIGRATION_12_13,
            ChatDatabase.MIGRATION_13_14,
        ).close()
    }

    @Test
    fun `an existing contact comes back unverified rather than pre-verified`() = runBlocking {
        insertV12Contact("peer_a", knownKey)
        insertV12Contact("peer_b", otherKey)

        runToCurrent()
        val room = migrateToCurrent()
        try {
            for (id in listOf("peer_a", "peer_b")) {
                assertNull(
                    "$id existed before 1.7b and nobody ever compared a safety code, " +
                        "so it must not arrive claiming the user verified it",
                    room.contactDao().getContactById(id)?.verifiedAt
                )
            }
        } finally {
            room.close()
        }
    }

    @Test
    fun `the pin and the key-change block survive the migration`() = runBlocking {
        insertV12Contact("peer_a", knownKey, otherKey, hasKeyChanged = true)

        runToCurrent()
        val room = migrateToCurrent()
        try {
            val contact = room.contactDao().getContactById("peer_a")!!
            // Both of these are security state. A migration that cleared either
            // would silently unblock a key the user never agreed to.
            assertEquals(knownKey, contact.pinnedPublicKey)
            assertEquals(true, contact.hasKeyChanged)

            val trust = ContactTrust(
                deviceId = contact.deviceId,
                displayName = contact.displayName,
                pinnedPublicKey = contact.pinnedPublicKey,
                observedPublicKey = contact.publicKeyBase64,
                verifiedAt = contact.verifiedAt,
            )
            assertEquals(TrustState.KEY_CHANGED, trust.state())
            assertEquals(true, trust.blocksSending())
        } finally {
            room.close()
        }
    }

    @Test
    fun `markVerified turns an unverified contact verified without touching the pin`() =
        runBlocking {
            insertV12Contact("peer_a", knownKey)
            runToCurrent()

            val room = migrateToCurrent()
            try {
                val dao = room.contactDao()
                assertEquals(TrustState.UNVERIFIED, trustOf(dao.getContactById("peer_a")!!).state())

                dao.markVerified("peer_a", 1_700_000_000_000L)

                val after = dao.getContactById("peer_a")!!
                assertEquals(1_700_000_000_000L, after.verifiedAt)
                assertEquals(TrustState.VERIFIED, trustOf(after).state())
                // Marking verified records that a comparison happened. It must not
                // be able to accept a new identity, so the pin is untouched.
                assertEquals(knownKey, after.pinnedPublicKey)
            } finally {
                room.close()
            }
        }

    @Test
    fun `a verification does not survive a key change`() = runBlocking {
        insertV12Contact("peer_a", knownKey)
        runToCurrent()

        val room = migrateToCurrent()
        try {
            val dao = room.contactDao()
            dao.markVerified("peer_a", 1_700_000_000_000L)
            assertEquals(TrustState.VERIFIED, trustOf(dao.getContactById("peer_a")!!).state())

            // The peer presents a different key. Yesterday's verification is not
            // evidence about today's key holder.
            dao.updatePublicKey("peer_a", otherKey)

            val after = dao.getContactById("peer_a")!!
            assertEquals(TrustState.KEY_CHANGED, trustOf(after).state())
            assertNull(
                "a code over two different keys would be read out as if it meant something",
                trustOf(after).safetyCode()
            )
        } finally {
            room.close()
        }
    }

    @Test
    fun `acceptNewKey clears the block and keeps the verification it cannot prove`() =
        runBlocking {
            insertV12Contact("peer_a", knownKey)
            runToCurrent()

            val room = migrateToCurrent()
            try {
                val dao = room.contactDao()
                dao.updatePublicKey("peer_a", otherKey)
                assertEquals(TrustState.KEY_CHANGED, trustOf(dao.getContactById("peer_a")!!).state())

                // The single exit from a key change, and only ever reached from an
                // explicit user action.
                dao.acceptNewKey("peer_a", otherKey)

                val after = dao.getContactById("peer_a")!!
                assertEquals(otherKey, after.pinnedPublicKey)
                assertEquals(false, after.hasKeyChanged)
                // Accepting a key is not the same as having compared codes, so the
                // contact comes back unverified and the user compares the new code.
                assertEquals(TrustState.UNVERIFIED, trustOf(after).state())
                assertEquals(true, trustOf(after).safetyCode() != null)
            } finally {
                room.close()
            }
        }

    private fun trustOf(contact: ContactEntity) = ContactTrust(
        deviceId = contact.deviceId,
        displayName = contact.displayName,
        pinnedPublicKey = contact.pinnedPublicKey,
        observedPublicKey = contact.publicKeyBase64,
        verifiedAt = contact.verifiedAt,
    )
}