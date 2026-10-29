package com.scrobblecue.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
abstract class CollectionDao {
    @Query("SELECT * FROM collection ORDER BY addedAt DESC")
    abstract fun all(): Flow<List<CollectionItem>>

    @Query("SELECT * FROM collection WHERE releaseId = :releaseId LIMIT 1")
    abstract suspend fun byRelease(releaseId: Long): CollectionItem?

    /** Replaces the whole collection in one step, so records removed on Discogs disappear here too. */
    @Transaction
    open suspend fun replaceAll(items: List<CollectionItem>) {
        deleteAll()
        insertAll(items)
    }

    @Query("DELETE FROM collection")
    protected abstract suspend fun deleteAll()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertAll(items: List<CollectionItem>)
}

@Dao
interface ReleaseDao {
    @Query("SELECT * FROM releases WHERE id = :id")
    suspend fun get(id: Long): CachedRelease?

    @Upsert
    suspend fun put(release: CachedRelease)

    @Query("SELECT * FROM prepared WHERE releaseId = :releaseId")
    suspend fun prepared(releaseId: Long): PreparedRelease?

    @Upsert
    suspend fun putPrepared(prepared: PreparedRelease)
}

@Dao
interface LookupDao {
    @Query("SELECT * FROM lookups WHERE releaseId = :releaseId")
    suspend fun get(releaseId: Long): LookupDurations?

    @Upsert
    suspend fun put(lookup: LookupDurations)
}

@Dao
interface CorrectionDao {
    @Query("SELECT * FROM corrections WHERE releaseId = :releaseId")
    suspend fun forRelease(releaseId: Long): List<Correction>

    @Upsert
    suspend fun put(correction: Correction)
}

@Dao
abstract class ScrobbleDao {
    @Query("SELECT * FROM plays WHERE id = :id")
    abstract fun observePlay(id: String): Flow<Play?>

    @Query("SELECT * FROM plays WHERE id = :id")
    abstract suspend fun play(id: String): Play?

    /** Scrobbles that may be sent at [now]: live ones only after their track has ended. */
    @Query("SELECT * FROM queue WHERE sendAfter IS NULL OR sendAfter <= :now ORDER BY playId, seq")
    abstract suspend fun dueQueue(now: String): List<QueuedScrobble>

    @Query("SELECT * FROM queue ORDER BY playId, seq")
    abstract suspend fun queue(): List<QueuedScrobble>

    @Query("SELECT COUNT(*) FROM queue WHERE playId = :playId")
    abstract suspend fun queuedCount(playId: String): Int

    /** A new play and its scrobbles are saved together, before anything is sent. */
    @Transaction
    open suspend fun addPlay(play: Play, scrobbles: List<QueuedScrobble>) {
        insertPlay(play)
        insertQueue(scrobbles)
    }

    /** Last.fm answered for these scrobbles: remove them and count the result on the play. */
    @Transaction
    open suspend fun confirm(playId: String, ids: List<String>, accepted: Int, ignored: Int) {
        deleteQueued(ids)
        val play = play(playId) ?: return
        val left = queuedCount(playId)
        updatePlay(
            play.copy(
                accepted = play.accepted + accepted,
                ignored = play.ignored + ignored,
                status = if (left == 0) PlayStatus.DONE else PlayStatus.SENDING,
                message = null,
            ),
        )
    }

    @Transaction
    open suspend fun markUncertain(playId: String, ids: List<String>, message: String) {
        setQueueState(ids, QueueState.UNCERTAIN)
        setPlayStatus(playId, PlayStatus.WAITING, message)
    }

    /**
     * Stopping live mode at [now]: the tracks in [keepTimestamps] (played long enough to count) are sent
     * right away; other tracks that have not finished are not scrobbled.
     */
    @Transaction
    open suspend fun stopLive(playId: String, now: String, keepTimestamps: List<String>) {
        if (keepTimestamps.isNotEmpty()) sendNow(playId, keepTimestamps, now)
        deleteUnfinished(playId, now)
        if (queuedCount(playId) == 0) setPlayStatus(playId, PlayStatus.DONE, null)
    }

    @Query("UPDATE queue SET sendAfter = :now WHERE playId = :playId AND timestamp IN (:timestamps)")
    protected abstract suspend fun sendNow(playId: String, timestamps: List<String>, now: String)

    @Query("DELETE FROM queue WHERE playId = :playId AND sendAfter > :now")
    protected abstract suspend fun deleteUnfinished(playId: String, now: String)

    @Query("UPDATE plays SET status = :status, message = :message WHERE id = :playId")
    abstract suspend fun setPlayStatus(playId: String, status: PlayStatus, message: String?)

    @Query("DELETE FROM queue WHERE id IN (:ids)")
    abstract suspend fun deleteQueued(ids: List<String>)

    @Query("UPDATE queue SET state = :state WHERE id IN (:ids)")
    protected abstract suspend fun setQueueState(ids: List<String>, state: QueueState)

    @Insert
    protected abstract suspend fun insertPlay(play: Play)

    @Insert
    protected abstract suspend fun insertQueue(scrobbles: List<QueuedScrobble>)

    @androidx.room.Update
    protected abstract suspend fun updatePlay(play: Play)
}

@Dao
interface MetaDao {
    @Query("SELECT value FROM meta WHERE `key` = :key")
    fun observe(key: String): Flow<String?>

    @Query("SELECT value FROM meta WHERE `key` = :key")
    suspend fun get(key: String): String?

    @Query("DELETE FROM meta WHERE `key` = :key")
    suspend fun delete(key: String)

    @Upsert
    suspend fun put(meta: Meta)
}
