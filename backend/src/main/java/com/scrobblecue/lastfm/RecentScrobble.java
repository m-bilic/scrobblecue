package com.scrobblecue.lastfm;

import java.time.Instant;

/**
 * A scrobble already on the user's Last.fm profile.
 */
public record RecentScrobble(String artist, String track, String album, Instant timestamp) {
}
