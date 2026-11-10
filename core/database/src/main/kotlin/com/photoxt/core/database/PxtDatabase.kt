package com.photoxt.core.database

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.DeleteColumn
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.AutoMigrationSpec
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * PhotoXT's database, the single source of truth for every screen (spec §5.3). Schema changes
 * bump [version] and ship with a migration and a migration test (spec §5.4).
 */
@Database(
    entities = [
        AlbumEntity::class,
        MediaEntity::class,
        MediaStoreCursorEntity::class,
        PlaybackPositionEntity::class,
        AccountEntity::class,
        TrackedFolderEntity::class,
        DriveFolderEntity::class,
        DriveThumbnailEntity::class,
        DriveCacheEntity::class,
        OfflinePinEntity::class,
        DriveOrphanEntity::class,
        TransferBatchEntity::class,
        TransferJobEntity::class,
        ClipboardEntity::class
    ],
    version = 5,
    // 2 (Phase 2): video resume points. 3 (Phase 3): Google accounts and Drive albums.
    // 4 (Phase 4): offline pins, orphans and albums kept offline.
    // 5 (Phase 5): the transfer queue and the clipboard.
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4, spec = Version3To4::class),
        AutoMigration(from = 4, to = 5)
    ]
)
abstract class PxtDatabase : RoomDatabase() {
    abstract fun mediaDao(): MediaDao

    abstract fun mediaIndexDao(): MediaIndexDao

    abstract fun albumDao(): AlbumDao

    abstract fun playbackDao(): PlaybackDao

    abstract fun driveDao(): DriveDao

    abstract fun driveCacheDao(): DriveCacheDao

    abstract fun transferDao(): TransferDao

    abstract fun fileOpsDao(): FileOpsDao
}

/** Pins moved from a never-set `drive_cache` column to the `offline_pin` table. */
@DeleteColumn(tableName = "drive_cache", columnName = "offlinePinned")
class Version3To4 : AutoMigrationSpec

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): PxtDatabase =
        Room.databaseBuilder(context, PxtDatabase::class.java, "photoxt.db").build()

    @Provides
    fun mediaDao(database: PxtDatabase): MediaDao = database.mediaDao()

    @Provides
    fun mediaIndexDao(database: PxtDatabase): MediaIndexDao = database.mediaIndexDao()

    @Provides
    fun albumDao(database: PxtDatabase): AlbumDao = database.albumDao()

    @Provides
    fun playbackDao(database: PxtDatabase): PlaybackDao = database.playbackDao()

    @Provides
    fun driveDao(database: PxtDatabase): DriveDao = database.driveDao()

    @Provides
    fun driveCacheDao(database: PxtDatabase): DriveCacheDao = database.driveCacheDao()

    @Provides
    fun transferDao(database: PxtDatabase): TransferDao = database.transferDao()

    @Provides
    fun fileOpsDao(database: PxtDatabase): FileOpsDao = database.fileOpsDao()
}
