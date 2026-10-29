package com.scrobblecue.app.data.live

import com.scrobblecue.app.api.models.TrackGroup
import com.scrobblecue.app.data.tracklist.Group
import com.scrobblecue.app.data.tracklist.Tracklist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Same cases as web/src/lib/live.test.ts. */
class LiveSessionTest {

    private fun track(position: String, startsAt: String, endsAt: String, scrobble: Boolean = true) =
        LiveTrack(position, position, "Artist", 0, startsAt, endsAt, scrobble)

    private val session = LiveSession(
        playId = "p1",
        tracklist = Tracklist(
            releaseId = 1,
            album = "Album",
            albumArtist = "Artist",
            sideScrobblable = true,
            groups = listOf(Group(TrackGroup.Kind.SIDE, "A", emptyList()), Group(TrackGroup.Kind.SIDE, "B", emptyList())),
            unassigned = emptyList(),
        ),
        cover = "",
        side = "A",
        tracks = listOf(
            track("A1", "2026-10-28T20:00:00Z", "2026-10-28T20:05:00Z"),
            track("A2", "2026-10-28T20:05:00Z", "2026-10-28T20:05:20Z", scrobble = false),
            track("A3", "2026-10-28T20:05:20Z", "2026-10-28T20:10:00Z"),
        ),
    )

    private fun at(iso: String) = Instant.parse(iso)

    @Test
    fun knowsWhichTrackIsPlaying() {
        assertEquals("A1", session.currentTrack(at("2026-10-28T20:04:59Z"))?.position)
        assertEquals("A2", session.currentTrack(at("2026-10-28T20:05:00Z"))?.position)
        assertNull(session.currentTrack(at("2026-10-28T20:10:00Z")))
    }

    @Test
    fun plansTheNextRunForWhenATrackEnds() {
        assertEquals(at("2026-10-28T20:05:00Z"), session.nextChange(at("2026-10-28T20:01:00Z")))
        assertNull(session.nextChange(at("2026-10-28T20:10:00Z")))
    }

    @Test
    fun aStoppedTrackCountsOnceHalfOrFourMinutesHavePlayed() {
        val short = track("A1", "2026-10-28T20:00:00Z", "2026-10-28T20:05:00Z").copy(durationSec = 300)
        assertFalse(short.playedEnough(at("2026-10-28T20:02:29Z")))
        assertTrue(short.playedEnough(at("2026-10-28T20:02:30Z")))

        val long = track("A1", "2026-10-28T20:00:00Z", "2026-10-28T20:12:00Z").copy(durationSec = 720)
        assertTrue(long.playedEnough(at("2026-10-28T20:04:00Z")))
    }

    @Test
    fun allowsFlippingDuringTheLastTrack() {
        assertFalse(session.onLastTrack(at("2026-10-28T20:05:10Z")))
        assertTrue(session.onLastTrack(at("2026-10-28T20:09:00Z")))
    }

    @Test
    fun offersTheNextSideWhenTheSideHasFinished() {
        assertFalse(session.finished(at("2026-10-28T20:09:59Z")))
        assertTrue(session.finished(at("2026-10-28T20:10:00Z")))
        assertEquals("B", session.nextSide()?.label)
        assertNull(session.copy(side = "B").nextSide())
    }
}
