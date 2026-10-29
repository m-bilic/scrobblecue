package com.scrobblecue.app.data.live

import com.scrobblecue.app.api.models.TrackGroup
import com.scrobblecue.app.data.tracklist.Group
import com.scrobblecue.app.data.tracklist.Tracklist
import kotlinx.serialization.Serializable
import java.time.Duration
import java.time.Instant

/** One track of the side being played live, with when it starts and ends. */
@Serializable
data class LiveTrack(
    val position: String,
    val title: String,
    val artist: String,
    val durationSec: Int,
    val startsAt: String,
    val endsAt: String,
    /** false for skipped and very short tracks: they play, but are not scrobbled. */
    val scrobble: Boolean,
) {
    val start: Instant get() = Instant.parse(startsAt)
    val end: Instant get() = Instant.parse(endsAt)

    /**
     * Last.fm's rule for an interrupted track: it counts once half of it, or 4 minutes, has played.
     * Used when the user stops or flips early, e.g. because the record ran ahead of the estimated lengths.
     */
    fun playedEnough(now: Instant): Boolean {
        val played = Duration.between(start, now).seconds
        return played > 0 && played >= minOf(durationSec / 2L, MAX_REQUIRED_SECONDS)
    }

    private companion object {
        const val MAX_REQUIRED_SECONDS = 240L
    }
}

/**
 * The side being played live (SPEC.md flow 8.6). Kept in the local database, so the app, the background
 * job and the notification all see the same state, also after the app was closed. Same as LiveSession
 * in web/src/lib/live.ts.
 */
@Serializable
data class LiveSession(
    val playId: String,
    val tracklist: Tracklist,
    val cover: String,
    val side: String,
    val tracks: List<LiveTrack>,
) {
    val start: Instant? get() = tracks.firstOrNull()?.start
    val end: Instant? get() = tracks.lastOrNull()?.end

    /** The track playing at [now], if any. */
    fun currentTrack(now: Instant): LiveTrack? = tracks.find { !now.isBefore(it.start) && now.isBefore(it.end) }

    /** The side has played to the end. */
    fun finished(now: Instant): Boolean = end?.let { !now.isBefore(it) } ?: true

    /** The next moment a track starts or ends, for the next background run. */
    fun nextChange(now: Instant): Instant? =
        tracks.flatMap { listOf(it.start, it.end) }.filter { it.isAfter(now) }.minOrNull()

    /** The last track is playing: the user may flip a little early. */
    fun onLastTrack(now: Instant): Boolean = tracks.isNotEmpty() && currentTrack(now) == tracks.last()

    /** The side after this one (B after A), for "Flip to side B?". */
    fun nextSide(): Group? {
        val sides = tracklist.groups.filter { it.kind == TrackGroup.Kind.SIDE }
        val index = sides.indexOfFirst { it.label == side }
        return if (index >= 0) sides.getOrNull(index + 1) else null
    }
}
