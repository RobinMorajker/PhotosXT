package com.photoxt.core.database

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Every schema version upgrades without losing rows (spec §5.4). */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        PxtDatabase::class.java
    )

    @Test
    fun version1To2KeepsTheIndexAndAddsResumePointsThatGoWithTheirItem() {
        helper.createDatabase(NAME, 1).use { db ->
            db.execSQL(
                "INSERT INTO album VALUES " +
                    "('device:1', 'DEVICE_FOLDER', 'external_primary:DCIM/Camera/', 'Camera')"
            )
            db.execSQL(
                "INSERT INTO media VALUES ('device:7', 'DEVICE', 'external_primary', " +
                    "'content://media/external/video/media/7', 'device:1', 'clip.mp4', " +
                    "'video/mp4', 'VIDEO', 100, 1920, 1080, 0, 60000, 1, 1, 0, 0, 0)"
            )
            db.execSQL("INSERT INTO media_store_cursor VALUES ('external_primary', 'v', 9, 'UTC')")
        }

        helper.runMigrationsAndValidate(NAME, 2, true).use { db ->
            assertEquals(1, db.count("media"))
            assertEquals(1, db.count("media_store_cursor"))
            db.execSQL("INSERT INTO playback_position VALUES ('device:7', 15000, 60000, 2)")
            db.execSQL("PRAGMA foreign_keys = ON")
            db.execSQL("DELETE FROM media WHERE id = 'device:7'")
            assertEquals(0, db.count("playback_position"))
        }
    }

    private fun SupportSQLiteDatabase.count(table: String): Int =
        query("SELECT COUNT(*) FROM $table").use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }

    private companion object {
        const val NAME = "migration-test.db"
    }
}
