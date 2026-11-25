package com.photoxt.core.database

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.DeleteColumn
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.AutoMigrationSpec
import androidx.sqlite.db.SupportSQLiteDatabase
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
        ClipboardEntity::class,
        AlbumPrefsEntity::class,
        AlbumGroupEntity::class,
        AlbumItemEntity::class,
        ItemOrderEntity::class,
        PinEntity::class,
        BookmarkCollectionEntity::class,
        BookmarkEntity::class,
        HistoryEntity::class,
        HiddenItemEntity::class,
        EditRecipeEntity::class,
        CollageProjectEntity::class
    ],
    version = 8,
    // 2 (Phase 2): video resume points. 3 (Phase 3): Google accounts and Drive albums.
    // 4 (Phase 4): offline pins, orphans and albums kept offline.
    // 5 (Phase 5): the transfer queue and the clipboard.
    // 6 (Phase 6): sort keys and dates, album options, groups, virtual and smart albums, pins,
    // bookmarks, history and hidden items; resume points lose their foreign key.
    // 7 (Phase 7): saved edits and what reverts them. 8 (Phase 8): collage drafts.
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4, spec = Version3To4::class),
        AutoMigration(from = 4, to = 5),
        AutoMigration(from = 5, to = 6, spec = Version5To6::class),
        AutoMigration(from = 6, to = 7),
        AutoMigration(from = 7, to = 8)
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

    abstract fun albumPrefsDao(): AlbumPrefsDao

    abstract fun virtualAlbumDao(): VirtualAlbumDao

    abstract fun pinDao(): PinDao

    abstract fun bookmarkDao(): BookmarkDao

    abstract fun historyDao(): HistoryDao

    abstract fun hiddenDao(): HiddenDao

    abstract fun backupDao(): BackupDao

    abstract fun editDao(): EditDao

    abstract fun collageDao(): CollageDao
}

/** Pins moved from a never-set `drive_cache` column to the `offline_pin` table. */
@DeleteColumn(tableName = "drive_cache", columnName = "offlinePinned")
class Version3To4 : AutoMigrationSpec

/**
 * Date added, the other date keys and name keys (spec §4.2) are new columns: every phone volume
 * is indexed again in full and every Drive folder scanned again, which fills them.
 */
class Version5To6 : AutoMigrationSpec {
    override fun onPostMigrate(db: SupportSQLiteDatabase) {
        db.execSQL("DELETE FROM media_store_cursor")
        db.execSQL("UPDATE tracked_folder SET state = 'SCANNING' WHERE state = 'READY'")
    }
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

    @Provides
    fun driveCacheDao(database: PxtDatabase): DriveCacheDao = database.driveCacheDao()

    @Provides
    fun transferDao(database: PxtDatabase): TransferDao = database.transferDao()

    @Provides
    fun fileOpsDao(database: PxtDatabase): FileOpsDao = database.fileOpsDao()

    @Provides
    fun editDao(database: PxtDatabase): EditDao = database.editDao()

    @Provides
    fun collageDao(database: PxtDatabase): CollageDao = database.collageDao()
}
