package com.scrobblecue.lastfm;

import java.time.Instant;

/**
 * One track to scrobble or show as "now playing".
 *
 * @param artist track artist
 * @param track track title
 * @param album album title, optional
 * @param albumArtist album artist if it differs from the track artist (compilations), optional
 * @param durationSec track length in seconds, optional
 * @param timestamp when the track started playing; ignored for "now playing"
 */
public record Scrobble(String artist, String track, String album, String albumArtist, Integer durationSec,
		Instant timestamp) {
}
