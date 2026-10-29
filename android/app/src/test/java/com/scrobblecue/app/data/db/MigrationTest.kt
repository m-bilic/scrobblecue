package com.scrobblecue.app.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Database upgrades keep the user's data, in particular unsent scrobbles. Uses the schemas exported to
 * app/schemas/.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    @Test
    fun version2AddsLiveModeAndKeepsQueuedScrobbles() {
        helper.createDatabase(db, 1).apply {
            execSQL(
                "INSERT INTO plays (id, releaseId, album, albumArtist, cover, scope, side, endedAt, createdAt, status, accepted, ignored, message) " +
                    "VALUES ('p1', 1, 'Album', 'Artist', '', 'SIDE', 'A', '2026-10-28T19:20:00Z', 0, 'WAITING', 0, 0, NULL)",
            )
            execSQL(
                "INSERT INTO queue (id, playId, seq, artist, track, album, albumArtist, durationSec, timestamp, state) " +
                    "VALUES ('q1', 'p1', 0, 'Artist', 'Track', 'Album', NULL, 200, '2026-10-28T19:00:00Z', 'PENDING')",
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(db, 2, true)

        migrated.query("SELECT sendAfter FROM queue WHERE id = 'q1'").use {
            assertTrue(it.moveToFirst())
            // Scrobbles from before live mode are sent right away
            assertTrue(it.isNull(0))
        }
        migrated.query("SELECT mode FROM plays WHERE id = 'p1'").use {
            assertTrue(it.moveToFirst())
            assertEquals("INSTANT", it.getString(0))
        }
    }

    // The full path, not just a name: Room's test helper otherwise trips over Windows paths
    private val db: String =
        RuntimeEnvironment.getApplication().getDatabasePath("migration-test.db").absolutePath
}
