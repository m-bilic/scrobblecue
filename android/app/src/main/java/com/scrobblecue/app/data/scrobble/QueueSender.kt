package com.scrobblecue.app.data.scrobble

import com.scrobblecue.app.api.apis.LastFmScrobbleControllerApi
import com.scrobblecue.app.api.models.RecentScrobble
import com.scrobblecue.app.api.models.ScrobbleItem
import com.scrobblecue.app.api.models.ScrobbleRequest
import com.scrobblecue.app.api.models.ScrobbleResult
import com.scrobblecue.app.data.api.ApiException
import com.scrobblecue.app.data.api.bodyOrThrow
import com.scrobblecue.app.data.api.toApiException
import com.scrobblecue.app.data.db.PlayStatus
import com.scrobblecue.app.data.db.QueueState
import com.scrobblecue.app.data.db.QueuedScrobble
import com.scrobblecue.app.data.db.ScrobbleDao
import com.scrobblecue.app.data.isoSeconds
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.time.Clock
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

enum class FlushResult {
    /** Everything that could be sent was sent. */
    DONE,

    /** Offline or Last.fm busy: try again later. */
    RETRY_LATER,

    /** Retrying won't help until the user acts (e.g. reconnects Last.fm). */
    NEEDS_USER,
}

/**
 * Sends the local scrobble queue to Last.fm. Scrobbles leave the queue only when Last.fm has answered
 * for them, so nothing is lost when the phone is offline or the app is closed mid-send.
 * Same rules as web/src/lib/scrobbler.ts.
 */
@Singleton
class QueueSender @Inject constructor(
    private val dao: ScrobbleDao,
    private val api: LastFmScrobbleControllerApi,
    private val clock: Clock,
) {
    private val running = Mutex()

    /** Sends everything in the queue. Calls while a send is running wait for it, then run once more. */
    suspend fun flush(): FlushResult = running.withLock {
        // Live-mode scrobbles stay queued until their track has ended
        for ((playId, items) in dao.dueQueue(isoSeconds(clock.instant())).groupBy { it.playId }) {
            val result = sendPlay(playId, items.sortedBy { it.seq })
            if (result != FlushResult.DONE) return@withLock result
        }
        FlushResult.DONE
    }

    private suspend fun sendPlay(playId: String, items: List<QueuedScrobble>): FlushResult {
        var remaining = items
        if (remaining.any { it.state == QueueState.UNCERTAIN }) {
            remaining = try {
                withoutAlreadyScrobbled(remaining)
            } catch (_: IOException) {
                return FlushResult.RETRY_LATER
            } catch (e: ApiException) {
                return stopFor(playId, e)
            }
        }

        for (batch in remaining.chunked(BATCH)) {
            val response = try {
                api.scrobble(ScrobbleRequest(batch.map { it.toItem() }))
            } catch (_: IOException) {
                // No answer at all (offline, timeout): the scrobbles may or may not have reached Last.fm
                dao.markUncertain(playId, batch.map { it.id }, "Offline – will be sent when the connection is back.")
                return FlushResult.RETRY_LATER
            }

            val results = response.body()?.results
            if (response.isSuccessful && results != null) {
                val accepted = results.count { it.status == ScrobbleResult.Status.ACCEPTED }
                dao.confirm(playId, batch.map { it.id }, accepted, results.size - accepted)
                continue
            }

            val error = response.toApiException()
            if (error.status == 502) {
                // Last.fm's answer was unreadable: it may have accepted the scrobbles
                dao.markUncertain(playId, batch.map { it.id }, error.message ?: "Last.fm did not answer clearly.")
                return FlushResult.RETRY_LATER
            }
            return stopFor(playId, error)
        }
        return FlushResult.DONE
    }

    private suspend fun stopFor(playId: String, error: ApiException): FlushResult {
        dao.setPlayStatus(playId, PlayStatus.WAITING, describe(error))
        return when {
            error.needsReconnect -> FlushResult.NEEDS_USER
            error.status == 429 || error.status >= 500 -> FlushResult.RETRY_LATER
            else -> FlushResult.NEEDS_USER
        }
    }

    /** Removes queued scrobbles that Last.fm already has (same track, same second). */
    private suspend fun withoutAlreadyScrobbled(items: List<QueuedScrobble>): List<QueuedScrobble> {
        val times = items.map { Instant.parse(it.timestamp) }
        val from = isoSeconds(times.min().minusSeconds(1))
        val to = isoSeconds(times.max().plusSeconds(1))
        val recent = api.recent(from, to).bodyOrThrow().scrobbles.orEmpty()
        val done = alreadyScrobbled(items, recent)
        if (done.isNotEmpty()) dao.deleteQueued(done.map { it.id })
        val doneIds = done.mapTo(HashSet()) { it.id }
        return items.filterNot { it.id in doneIds }
    }

    private companion object {
        /** Last.fm accepts at most 50 scrobbles per request. */
        const val BATCH = 50
    }
}

/** Queued scrobbles found among the user's recent Last.fm scrobbles, matched by track name and second. */
fun alreadyScrobbled(items: List<QueuedScrobble>, recent: List<RecentScrobble>): List<QueuedScrobble> {
    fun key(track: String, timestamp: String) = "${track.trim().lowercase()}|${Instant.parse(timestamp).epochSecond}"
    val seen = recent.mapNotNullTo(HashSet()) { r ->
        if (r.track == null || r.timestamp == null) null else key(r.track, r.timestamp)
    }
    return items.filter { key(it.track, it.timestamp) in seen }
}

private fun QueuedScrobble.toItem() = ScrobbleItem(
    artist = artist,
    timestamp = timestamp,
    track = track,
    album = album,
    albumArtist = albumArtist,
    durationSec = durationSec,
)

private fun describe(error: ApiException): String = when {
    error.needsReconnect -> "Last.fm needs to be connected again. Your scrobbles are kept and sent afterwards."
    error.code == "lastfm-unavailable" || error.code == "lastfm-rate-limited" ->
        "Last.fm is busy right now. Your scrobbles are kept and sent later."
    else -> error.message ?: "Sending failed."
}
