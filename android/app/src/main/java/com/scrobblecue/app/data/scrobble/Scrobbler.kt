package com.scrobblecue.app.data.scrobble

import com.scrobblecue.app.api.apis.TimelineControllerApi
import com.scrobblecue.app.api.models.TimelineRequest
import com.scrobblecue.app.api.models.TrackInput
import com.scrobblecue.app.data.api.bodyOrThrow
import com.scrobblecue.app.data.db.Play
import com.scrobblecue.app.data.db.PlayScope
import com.scrobblecue.app.data.db.PlayStatus
import com.scrobblecue.app.data.db.QueueState
import com.scrobblecue.app.data.db.QueuedScrobble
import com.scrobblecue.app.data.db.ScrobbleDao
import com.scrobblecue.app.data.isoSeconds
import com.scrobblecue.app.data.tracklist.Track
import com.scrobblecue.app.data.tracklist.Tracklist
import com.scrobblecue.app.work.QueueScheduler
import java.io.IOException
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** What the user tapped: a side, the whole record or selected tracks, finished at [endedAt]. */
data class ScrobbleInput(
    val tracklist: Tracklist,
    val cover: String,
    val scope: PlayScope,
    val side: String? = null,
    /** In play order. */
    val tracks: List<Track>,
    val endedAt: Instant,
)

/**
 * Instant mode (SPEC.md flow 8.5): computes timestamps so the last track ends at `endedAt`, saves the
 * play and its scrobbles to the local queue, then sends. If sending fails, WorkManager retries later.
 */
@Singleton
class Scrobbler @Inject constructor(
    private val timelineApi: TimelineControllerApi,
    private val dao: ScrobbleDao,
    private val sender: QueueSender,
    private val scheduler: QueueScheduler,
) {
    /** @return the id of the new play, to follow its status */
    suspend fun scrobble(input: ScrobbleInput): String {
        val timeline = try {
            timelineApi.timeline(
                TimelineRequest(
                    tracks = input.tracks.map { TrackInput(it.position, it.durationSec, it.scrobblable) },
                    endedAt = isoSeconds(input.endedAt),
                ),
            ).bodyOrThrow().tracks.orEmpty()
        } catch (_: IOException) {
            // Backend unreachable (airplane mode): same calculation on the phone
            offlineTimeline(input.tracks, input.endedAt)
        }

        val playId = UUID.randomUUID().toString()
        val queued = timeline.mapIndexedNotNull { i, entry ->
            val track = input.tracks[i]
            if (entry.scrobble != true || entry.startsAt == null) return@mapIndexedNotNull null
            QueuedScrobble(
                id = UUID.randomUUID().toString(),
                playId = playId,
                seq = i,
                artist = track.artist,
                track = track.title,
                album = input.tracklist.album,
                // Last.fm wants albumArtist only when it differs from the track artist (compilations)
                albumArtist = input.tracklist.albumArtist.takeIf { it != track.artist },
                durationSec = track.durationSec,
                timestamp = entry.startsAt,
                state = QueueState.PENDING,
            )
        }
        val play = Play(
            id = playId,
            releaseId = input.tracklist.releaseId,
            album = input.tracklist.album,
            albumArtist = input.tracklist.albumArtist,
            cover = input.cover,
            scope = input.scope,
            side = input.side,
            endedAt = isoSeconds(input.endedAt),
            createdAt = System.currentTimeMillis(),
            status = if (queued.isEmpty()) PlayStatus.DONE else PlayStatus.SENDING,
            message = if (queued.isEmpty()) "No tracks long enough to scrobble." else null,
        )
        dao.addPlay(play, queued)

        if (sender.flush() == FlushResult.RETRY_LATER) scheduler.sendWhenOnline()
        return playId
    }
}
