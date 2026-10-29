package com.scrobblecue.app.data.db

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
 * The app's local database. Every schema change needs a migration, so the phone keeps its data on
 * update (schemas are exported to app/schemas/ so migrations can be generated and tested).
 */
@Database(
    entities = [
        CollectionItem::class,
        CachedRelease::class,
        PreparedRelease::class,
        LookupDurations::class,
        Correction::class,
        Play::class,
        QueuedScrobble::class,
        Meta::class,
    ],
    version = 2,
    autoMigrations = [
        // 2: live mode (queue.sendAfter, plays.mode)
        AutoMigration(from = 1, to = 2),
    ],
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun collection(): CollectionDao
    abstract fun releases(): ReleaseDao
    abstract fun lookups(): LookupDao
    abstract fun corrections(): CorrectionDao
    abstract fun scrobbles(): ScrobbleDao
    abstract fun meta(): MetaDao
}

/** Keys of the `meta` table. */
object MetaKeys {
    const val COLLECTION_SYNCED_AT = "collectionSyncedAt"
    const val LIVE_SESSION = "liveSession"
}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "scrobblecue.db").build()

    @Provides
    fun collectionDao(db: AppDatabase) = db.collection()

    @Provides
    fun releaseDao(db: AppDatabase) = db.releases()

    @Provides
    fun lookupDao(db: AppDatabase) = db.lookups()

    @Provides
    fun correctionDao(db: AppDatabase) = db.corrections()

    @Provides
    fun scrobbleDao(db: AppDatabase) = db.scrobbles()

    @Provides
    fun metaDao(db: AppDatabase) = db.meta()
}
