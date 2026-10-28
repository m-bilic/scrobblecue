package com.scrobblecue.tracklist;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/**
 * A user's correction for one track, stored on their device and sent along with each preparation.
 * {@code null} fields keep the Discogs value.
 *
 * @param position which track, e.g. {@code A1}
 * @param title corrected title
 * @param artist corrected artist
 * @param durationSec corrected length
 * @param skip never scrobble this track (e.g. a locked groove or spoken intro)
 */
public record TrackCorrection(@NotBlank String position, String title, String artist, @Positive Integer durationSec,
		boolean skip) {
}
