package com.scrobblecue.app.data.live

import com.scrobblecue.app.api.apis.LastFmScrobbleControllerApi
import com.scrobblecue.app.api.apis.TimelineControllerApi
import com.scrobblecue.app.api.models.NowPlayingRequest
import com.scrobblecue.app.api.models.TimelineRequest
import com.scrobblecue.app.api.models.TrackInput
import com.scrobblecue.app.data.api.ApiJson
import com.scrobblecue.app.data.api.bodyOrThrow
import com.scrobblecue.app.data.db.Meta
import com.scrobblecue.app.data.db.MetaDao
import com.scrobblecue.app.data.db.MetaKeys
import com.scrobblecue.app.data.db.Play
import com.scrobblecue.app.data.db.PlayMode
import com.scrobblecue.app.data.db.PlayScope
import com.scrobblecue.app.data.db.PlayStatus
import com.scrobblecue.app.data.db.QueueState
import com.scrobblecue.app.data.db.QueuedScrobble
import com.scrobblecue.app.data.db.ScrobbleDao
import com.scrobblecue.app.data.isoSeconds
import com.scrobblecue.app.data.scrobble.FlushResult
import com.scrobblecue.app.data.scrobble.QueueSender
import com.scrobblecue.app.data.scrobble.offlineTimelineFrom
import com.scrobblecue.app.data.tracklist.Group
import com.scrobblecue.app.data.tracklist.Tracklist
import com.scrobblecue.app.work.QueueScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.time.Clock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Live mode: the user starts a side as the needle drops, each track is scrobbled when it has finished
 * playing, and Last.fm shows it as "now playing" meanwhile.
 *
 * All scrobbles are queued at the start, each with `sendAfter` = the end of its track, so nothing is lost
 * when the app is closed or the phone restarts. A background job ([tick]) runs at each track change to
 * send what is due, update "now playing" and the notification. If Android delays the job, scrobbles keep
 * their correct timestamps; only "now playing" lags.
 */
@Singleton
class LiveRepository @Inject constructor(
    private val timelineApi: TimelineControllerApi,
    private val lastFm: LastFmScrobbleControllerApi,
    private val scrobbles: ScrobbleDao,
    private val meta: MetaDao,
    private val sender: QueueSender,
    private val scheduler: QueueScheduler,
    private val notifications: LiveNotifications,
    private val clock: Clock,
) {
    private val _session = MutableStateFlow<LiveSession?>(null)
    val session: StateFlow<LiveSession?> = _session.asStateFlow()

    private val lock = Mutex()
    private var loaded = false

    /** Track for which "now playing" was last sent (per app process; a repeat is harmless). */
    private var nowPlayingSent: String? = null

    /** Loads a side that was playing when the app was closed and catches up with it. */
    suspend fun resume() {
        load()
        if (_session.value != null) scheduler.liveTick(clock.instant())
    }

    /** Starts playing [side] live; a side that is still playing is stopped first. */
    suspend fun start(tracklist: Tracklist, side: Group, cover: String) {
        stop()
        val startedAt = clock.instant()
        val timeline = try {
            timelineApi.timeline(
                TimelineRequest(
                    tracks = side.tracks.map { TrackInput(it.position, it.durationSec, it.scrobblable) },
                    startedAt = isoSeconds(startedAt),
                ),
            ).bodyOrThrow().tracks.orEmpty()
        } catch (_: IOException) {
            // Backend unreachable: same calculation on the phone (SPEC.md D21)
            offlineTimelineFrom(side.tracks, startedAt)
        }

        val tracks = timeline.mapIndexed { i, entry ->
            val track = side.tracks[i]
            LiveTrack(
                position = track.position,
                title = track.title,
                artist = track.artist,
                durationSec = track.durationSec,
                startsAt = entry.startsAt ?: isoSeconds(startedAt),
                endsAt = entry.endsAt ?: isoSeconds(startedAt),
                scrobble = entry.scrobble == true,
            )
        }
        val playId = UUID.randomUUID().toString()
        val queued = tracks.mapIndexedNotNull { i, track ->
            if (!track.scrobble) return@mapIndexedNotNull null
            QueuedScrobble(
                id = UUID.randomUUID().toString(),
                playId = playId,
                seq = i,
                artist = track.artist,
                track = track.title,
                album = tracklist.album,
                albumArtist = tracklist.albumArtist.takeIf { it != track.artist },
                durationSec = track.durationSec,
                timestamp = track.startsAt,
                state = QueueState.PENDING,
                sendAfter = track.endsAt,
            )
        }
        val play = Play(
            id = playId,
            releaseId = tracklist.releaseId,
            album = tracklist.album,
            albumArtist = tracklist.albumArtist,
            cover = cover,
            scope = PlayScope.SIDE,
            side = side.label,
            endedAt = tracks.lastOrNull()?.endsAt ?: isoSeconds(startedAt),
            createdAt = clock.millis(),
            status = if (queued.isEmpty()) PlayStatus.DONE else PlayStatus.SENDING,
            mode = PlayMode.LIVE,
        )
        val session = LiveSession(playId, tracklist, cover, side.label, tracks)
        scrobbles.addPlay(play, queued)
        save(session)
        tick()
    }

    /**
     * Stops the side. Finished tracks are sent as usual, and so is the track playing now if enough of it
     * has played (Last.fm's rule; the record may run ahead of the estimated lengths). Others are dropped.
     */
    suspend fun stop() {
        load()
        val current = _session.value ?: return
        val now = clock.instant()
        val keep = current.tracks.filter { it.scrobble && it.end.isAfter(now) && it.playedEnough(now) }
        scrobbles.stopLive(current.playId, isoSeconds(now), keep.map { it.startsAt })
        clear()
        scheduler.sendWhenOnline()
    }

    /** After the last side: the user is done. */
    suspend fun end() {
        load()
        if (_session.value != null) clear()
    }

    /** Starts the side after this one ("Flip to side B?"), also during its last track (an early flip). */
    suspend fun startNextSide() {
        load()
        val current = _session.value ?: return
        val next = current.nextSide() ?: return end()
        start(current.tracklist, next, current.cover)
    }

    /**
     * Runs at every track change (from [com.scrobblecue.app.work.LiveTickWorker]) and right after start:
     * sends what is due, updates "now playing" and the notification, and plans the next run.
     */
    suspend fun tick() = lock.withLock {
        load()
        val current = _session.value ?: return@withLock
        val now = clock.instant()

        if (sender.flush() == FlushResult.RETRY_LATER) scheduler.sendWhenOnline()

        val track = current.currentTrack(now)
        if (track != null && track.scrobble && "${current.playId}|${track.position}" != nowPlayingSent) {
            nowPlayingSent = "${current.playId}|${track.position}"
            sendNowPlaying(current, track)
        }

        if (current.finished(now)) {
            notifications.showFinished(current)
        } else {
            notifications.showPlaying(current, track)
            current.nextChange(now)?.let { scheduler.liveTick(it) }
        }
    }

    /** "Now playing" is only a display on Last.fm: failures are ignored. */
    private suspend fun sendNowPlaying(session: LiveSession, track: LiveTrack) {
        try {
            lastFm.nowPlaying(
                NowPlayingRequest(
                    artist = track.artist,
                    track = track.title,
                    album = session.tracklist.album,
                    albumArtist = session.tracklist.albumArtist.takeIf { it != track.artist },
                    durationSec = track.durationSec,
                ),
            )
        } catch (_: IOException) {
            // Offline: the scrobble itself is queued anyway
        }
    }

    private suspend fun load() {
        if (loaded) return
        _session.value = meta.get(MetaKeys.LIVE_SESSION)?.let { ApiJson.decodeFromString<LiveSession>(it) }
        loaded = true
    }

    private suspend fun save(session: LiveSession) {
        meta.put(Meta(MetaKeys.LIVE_SESSION, ApiJson.encodeToString(session)))
        _session.value = session
        loaded = true
    }

    private suspend fun clear() {
        meta.delete(MetaKeys.LIVE_SESSION)
        _session.value = null
        scheduler.cancelLiveTick()
        notifications.cancel()
    }
}
