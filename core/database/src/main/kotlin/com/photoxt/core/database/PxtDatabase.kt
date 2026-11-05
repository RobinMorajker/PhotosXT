package com.photoxt.core.database

import android.content.Context
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
    entities = [AlbumEntity::class, MediaEntity::class, MediaStoreCursorEntity::class],
    version = 1
)
abstract class PxtDatabase : RoomDatabase() {
    abstract fun mediaDao(): MediaDao

    abstract fun mediaIndexDao(): MediaIndexDao

    abstract fun albumDao(): AlbumDao
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
}
