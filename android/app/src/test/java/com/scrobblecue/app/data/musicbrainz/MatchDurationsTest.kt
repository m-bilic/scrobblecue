package com.scrobblecue.app.data.musicbrainz

import org.junit.Assert.assertEquals
import org.junit.Test

/** Same cases as web/src/external/musicbrainz.test.ts. */
class MatchDurationsTest {

    @Test
    fun matchesByTrackNumberWhenMusicBrainzUsesVinylPositions() {
        // Real data: MusicBrainz release for Discogs release 1 (The Persuader – Stockholm)
        val mb = listOf(
            MbTrack("A", 285000),
            MbTrack("B1", 371000),
            MbTrack("B2", 169000),
            MbTrack("C1", 338000),
            MbTrack("C2", 292000),
            MbTrack("D", 316000),
        )

        assertEquals(
            mapOf("A" to 285, "B1" to 371, "B2" to 169, "C1" to 338, "C2" to 292, "D" to 316),
            matchDurations(listOf("A", "B1", "B2", "C1", "C2", "D"), mb),
        )
    }

    @Test
    fun fallsBackToTrackOrderWhenNumbersDifferButCountsMatch() {
        val mb = listOf(MbTrack("1", 200400), MbTrack("2", null))

        assertEquals(mapOf("A1" to 200), matchDurations(listOf("A1", "B1"), mb))
    }

    @Test
    fun givesUpWhenNeitherNumbersNorCountsMatch() {
        assertEquals(emptyMap<String, Int>(), matchDurations(listOf("A1", "A2", "B1"), listOf(MbTrack("1", 1000))))
    }
}
