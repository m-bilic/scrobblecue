package com.scrobblecue.app.data.scrobble

import com.scrobblecue.app.api.models.PreparedTrack.DurationSource
import com.scrobblecue.app.data.tracklist.Track
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

/**
 * The offline copy must give the same result as the backend's TimelineCalculator
 * (backend/src/test/java/com/scrobblecue/timeline/TimelineCalculatorTests.java).
 */
class OfflineTimelineTest {

    private fun track(position: String, seconds: Int, scrobblable: Boolean = true) =
        Track(position, "Track $position", "Artist", seconds, DurationSource.DISCOGS, scrobblable)

    @Test
    fun countsBackwardsFromTheEndOfTheLastTrack() {
        val tracks = listOf(track("A1", 200), track("A2", 20), track("A3", 300))

        val timeline = offlineTimeline(tracks, Instant.parse("2026-10-28T20:00:00Z"))

        // 200 + 20 + 300 s = 8:40 before 20:00
        assertEquals(listOf("2026-10-28T19:51:20Z", "2026-10-28T19:54:40Z", "2026-10-28T19:55:00Z"), timeline.map { it.startsAt })
        assertEquals(listOf("2026-10-28T19:54:40Z", "2026-10-28T19:55:00Z", "2026-10-28T20:00:00Z"), timeline.map { it.endsAt })
    }

    @Test
    fun liveModeCountsForwardFromTheStart() {
        val tracks = listOf(track("A1", 200), track("A2", 300))

        val timeline = offlineTimelineFrom(tracks, Instant.parse("2026-10-28T20:00:00Z"))

        assertEquals(listOf("2026-10-28T20:00:00Z", "2026-10-28T20:03:20Z"), timeline.map { it.startsAt })
        assertEquals(listOf("2026-10-28T20:03:20Z", "2026-10-28T20:08:20Z"), timeline.map { it.endsAt })
    }

    @Test
    fun skipsShortAndSkippedTracksButKeepsTheirTime() {
        val tracks = listOf(track("A1", 31), track("A2", 30), track("A3", 200, scrobblable = false))

        val timeline = offlineTimeline(tracks, Instant.parse("2026-10-28T20:00:00Z"))

        assertEquals(listOf(true, false, false), timeline.map { it.scrobble })
        assertEquals("2026-10-28T19:55:39Z", timeline.first().startsAt)
    }
}
