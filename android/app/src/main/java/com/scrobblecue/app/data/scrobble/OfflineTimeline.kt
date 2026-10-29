package com.scrobblecue.app.data.scrobble

import com.scrobblecue.app.api.models.TimelineEntry
import com.scrobblecue.app.data.isoSeconds
import com.scrobblecue.app.data.tracklist.Track
import java.time.Instant

/** Last.fm does not count tracks of 30 seconds or less. */
private const val MIN_SCROBBLE_SECONDS = 31

/**
 * Timestamps without the backend, so scrobbling works in airplane mode. The backend's
 * TimelineCalculator is the reference; this copy must follow the same rules (see OfflineTimelineTest).
 *
 * Instant mode: the last track ends at [endedAt], everything before is counted backwards.
 */
fun offlineTimeline(tracks: List<Track>, endedAt: Instant): List<TimelineEntry> =
    offlineTimelineFrom(tracks, endedAt.minusSeconds(tracks.sumOf { it.durationSec.toLong() }))

/** Live mode: the first track starts at [startedAt], each next one when the previous ends. */
fun offlineTimelineFrom(tracks: List<Track>, startedAt: Instant): List<TimelineEntry> {
    var start = startedAt
    return tracks.map { track ->
        val end = start.plusSeconds(track.durationSec.toLong())
        TimelineEntry(
            position = track.position,
            startsAt = isoSeconds(start),
            endsAt = isoSeconds(end),
            scrobble = track.scrobblable && track.durationSec >= MIN_SCROBBLE_SECONDS,
        ).also { start = end }
    }
}
