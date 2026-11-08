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
        PxtDatabase::class.java,
        listOf(Version3To4())
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

    @Test
    fun version2To3KeepsDeviceItemsAndAddsDriveTablesThatGoWithTheirItems() {
        helper.createDatabase(NAME, 2).use { db ->
            db.execSQL(
                "INSERT INTO album VALUES " +
                    "('device:1', 'DEVICE_FOLDER', 'external_primary:DCIM/Camera/', 'Camera')"
            )
            db.execSQL(
                "INSERT INTO media VALUES ('device:7', 'DEVICE', 'external_primary', " +
                    "'content://media/external/images/media/7', 'device:1', 'a.jpg', " +
                    "'image/jpeg', 'IMAGE', 100, 4000, 3000, 0, NULL, 1, 1, 0, 0, 0)"
            )
            db.execSQL("INSERT INTO playback_position VALUES ('device:7', 1, 2, 3)")
        }

        helper.runMigrationsAndValidate(NAME, 3, true).use { db ->
            assertEquals(1, db.count("media"))
            assertEquals(1, db.count("playback_position"))
            // Device rows read back with empty Drive columns.
            db.query(
                "SELECT m.remoteId, m.accountId, a.parentId FROM media m " +
                    "JOIN album a ON a.id = m.albumId"
            ).use {
                it.moveToFirst()
                assertEquals(null, it.getString(0))
                assertEquals(null, it.getString(1))
                assertEquals(null, it.getString(2))
            }
            db.execSQL(
                "INSERT INTO account VALUES ('a', 'a@example.com', NULL, NULL, '', 0, NULL, " +
                    "NULL, 1)"
            )
            db.execSQL(
                "INSERT INTO album (id, type, sourceRef, title, accountId, parentId, rootId) " +
                    "VALUES ('drive:a:f', 'DRIVE_FOLDER', 'drive:F/', 'F', 'a', NULL, 'drive:a:f')"
            )
            db.execSQL(
                "INSERT INTO tracked_folder VALUES ('drive:a:f', 'a', 'f', NULL, 0, 'READY', 1)"
            )
            db.execSQL("INSERT INTO drive_folder VALUES ('a', 'f', NULL, 'drive:a:f', 'F')")
            db.execSQL(
                "INSERT INTO media (id, source, volume, contentUri, albumId, name, mime, kind, " +
                    "bytes, width, height, rotation, durationMs, takenAt, modifiedAt, dayKey, " +
                    "monthKey, favorite, accountId, remoteId, remoteVersion) VALUES " +
                    "('drive:a:x', 'DRIVE', NULL, NULL, 'drive:a:f', 'x.jpg', 'image/jpeg', " +
                    "'IMAGE', 1, 1, 1, 0, NULL, 1, 1, 0, 0, 0, 'a', 'x', 3)"
            )
            db.execSQL("INSERT INTO drive_thumbnail VALUES ('drive:a:x', 3, 'LKO2', 0, 1)")
            db.execSQL("INSERT INTO drive_cache VALUES ('drive:a:x', '/f', 3, 1, 1, 1, 0)")
            db.execSQL("PRAGMA foreign_keys = ON")
            db.execSQL("DELETE FROM media WHERE id = 'drive:a:x'")
            assertEquals(0, db.count("drive_thumbnail"))
            assertEquals(0, db.count("drive_cache"))
            db.execSQL("DELETE FROM account WHERE id = 'a'")
            assertEquals(0, db.count("tracked_folder"))
            assertEquals(0, db.count("drive_folder"))
        }
    }

    @Test
    fun version3To4KeepsCachedOriginalsAndAddsPinsThatGoWithTheirItem() {
        helper.createDatabase(NAME, 3).use { db ->
            db.execSQL(
                "INSERT INTO account VALUES ('a', 'a@example.com', NULL, NULL, '', 0, 't', " +
                    "NULL, 1)"
            )
            db.execSQL(
                "INSERT INTO album (id, type, sourceRef, title, accountId, parentId, rootId) " +
                    "VALUES ('drive:a:f', 'DRIVE_FOLDER', 'drive:F/', 'F', 'a', NULL, 'drive:a:f')"
            )
            db.execSQL(
                "INSERT INTO tracked_folder VALUES ('drive:a:f', 'a', 'f', NULL, 0, 'READY', 1)"
            )
            db.execSQL(
                "INSERT INTO media (id, source, volume, contentUri, albumId, name, mime, kind, " +
                    "bytes, width, height, rotation, durationMs, takenAt, modifiedAt, dayKey, " +
                    "monthKey, favorite, accountId, remoteId, remoteVersion) VALUES " +
                    "('drive:a:x', 'DRIVE', NULL, NULL, 'drive:a:f', 'x.jpg', 'image/jpeg', " +
                    "'IMAGE', 1, 1, 1, 0, NULL, 1, 1, 0, 0, 0, 'a', 'x', 3)"
            )
            db.execSQL("INSERT INTO drive_cache VALUES ('drive:a:x', '/f', 3, 1, 2, 5, 0)")
        }

        helper.runMigrationsAndValidate(NAME, 4, true).use { db ->
            db.query("SELECT path, lastOpenedAt FROM drive_cache").use {
                it.moveToFirst()
                assertEquals("/f", it.getString(0))
                assertEquals(5L, it.getLong(1))
            }
            db.query("SELECT offline FROM tracked_folder").use {
                it.moveToFirst()
                assertEquals(0, it.getInt(0))
            }
            db.execSQL("INSERT INTO offline_pin VALUES ('drive:a:x', 9)")
            db.execSQL(
                "INSERT INTO drive_orphan VALUES ('a', 'y', 2, NULL, 1, 'image/jpeg', 0, NULL, 0)"
            )
            db.execSQL("PRAGMA foreign_keys = ON")
            db.execSQL("DELETE FROM media WHERE id = 'drive:a:x'")
            assertEquals(0, db.count("offline_pin"))
            assertEquals(0, db.count("drive_cache"))
            assertEquals(1, db.count("drive_orphan"))
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
