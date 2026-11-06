package com.photoxt.core.database

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
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
        DriveCacheEntity::class
    ],
    version = 3,
    // 2 (Phase 2): video resume points. 3 (Phase 3): Google accounts and Drive albums.
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3)]
)
abstract class PxtDatabase : RoomDatabase() {
    abstract fun mediaDao(): MediaDao

    abstract fun mediaIndexDao(): MediaIndexDao

    abstract fun albumDao(): AlbumDao

    abstract fun playbackDao(): PlaybackDao

    abstract fun driveDao(): DriveDao
}

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
}
