package com.scrobblecue.tracklist;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A release ready to scrobble: tracks grouped by side (vinyl) or disc, cleaned, with corrections applied.
 *
 * @param releaseId Discogs release id
 * @param album album title for Last.fm
 * @param albumArtist release artist for Last.fm's {@code albumArtist}
 * @param sideScrobblable whether one-tap side scrobbling applies (vinyl with lettered sides)
 * @param groups sides ({@code A}, {@code B}, …), discs, or a single {@code ALL} group, in play order
 * @param unassigned tracks without a usable position; selectable manually
 */
public record PreparedTracklist(long releaseId, String album, String albumArtist, boolean sideScrobblable,
		List<Group> groups, List<Track> unassigned) {

	public enum GroupKind {

		SIDE, DISC, ALL

	}

	/**
	 * @param kind what the label means
	 * @param label {@code A}, {@code B}, {@code 1}, … or {@code ALL}
	 */
	@Schema(name = "TrackGroup")
	public record Group(GroupKind kind, String label, List<Track> tracks) {
	}

	public enum DurationSource {

		/** The user's correction. */
		CORRECTION,
		/** Discogs' tracklist. */
		DISCOGS,
		/** Looked up by the client (MusicBrainz, Last.fm). */
		LOOKUP,
		/** No source knew it: 3:30. */
		DEFAULT

	}

	/**
	 * @param position Discogs position, e.g. {@code A1}
	 * @param artist track artist for Last.fm
	 * @param title track title for Last.fm
	 * @param durationSec length used for timestamps
	 * @param durationSource where the length came from
	 * @param scrobblable {@code false} for tracks of 30 seconds or less (Last.fm rule) and tracks the user
	 * marked as skipped; they still count for timing
	 */
	@Schema(name = "PreparedTrack")
	public record Track(String position, String artist, String title, int durationSec, DurationSource durationSource,
			boolean scrobblable) {
	}

}
