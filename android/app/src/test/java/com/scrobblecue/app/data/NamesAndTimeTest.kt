package com.scrobblecue.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

/** Same cases as web/src/lib/names.test.ts and time.test.ts. */
class NamesAndTimeTest {

    @Test
    fun cleanNameRemovesDiscogsSuffixAndExtraSpaces() {
        assertEquals("Prince", cleanName("Prince (2)"))
        assertEquals("Boillat Thérace Quintet", cleanName("  Boillat   Thérace Quintet "))
        assertEquals("Love (Remix)", cleanName("Love (Remix)"))
        assertEquals("", cleanName(null))
    }

    @Test
    fun joinArtistsUsesDiscogsJoins() {
        assertEquals(
            "Simon & Garfunkel",
            joinArtists(listOf(ArtistCredit("Simon (3)", "&"), ArtistCredit("Garfunkel"))),
        )
        assertEquals("A, B", joinArtists(listOf(ArtistCredit("A", ","), ArtistCredit("B"))))
        assertEquals("A Feat. B", joinArtists(listOf(ArtistCredit("A", "Feat."), ArtistCredit("B"))))
        assertEquals("Various Artists", joinArtists(listOf(ArtistCredit("Various"))))
    }

    @Test
    fun searchableIgnoresCaseAndAccents() {
        assertEquals("boillat therace quintet", searchable("Boillat Thérace Quintet"))
    }

    @Test
    fun durationsFormatAndParse() {
        assertEquals("3:33", formatDuration(213))
        assertEquals("1:02:15", formatDuration(3735))
        assertEquals(213, parseDuration("3:33"))
        assertEquals(3735, parseDuration(" 1:02:15 "))
        assertNull(parseDuration("3.33"))
        assertNull(parseDuration("0:00"))
    }

    @Test
    fun timeAgoInWords() {
        val now = Instant.parse("2026-10-28T12:00:00Z")
        assertEquals("just now", timeAgo(now.minusSeconds(20), now))
        assertEquals("5 min ago", timeAgo(now.minusSeconds(300), now))
        assertEquals("3 h ago", timeAgo(now.minusSeconds(3 * 3600), now))
        assertEquals("yesterday", timeAgo(now.minusSeconds(26 * 3600), now))
    }
}
