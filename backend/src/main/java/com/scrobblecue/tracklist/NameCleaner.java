package com.scrobblecue.tracklist;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Turns Discogs names into what Last.fm expects. Deliberately minimal: Last.fm applies its own
 * corrections, so only Discogs-specific artefacts are removed.
 */
final class NameCleaner {

	/** Discogs disambiguation suffix, e.g. {@code Prince (2)}. */
	private static final Pattern DISAMBIGUATION = Pattern.compile("\\s+\\(\\d+\\)$");

	private static final Pattern SPACES = Pattern.compile("\\s+");

	private NameCleaner() {
	}

	static String clean(String name) {
		if (name == null) {
			return "";
		}
		String trimmed = SPACES.matcher(name.trim()).replaceAll(" ");
		return DISAMBIGUATION.matcher(trimmed).replaceAll("");
	}

	/**
	 * Joins Discogs artist credits using their {@code join} values, e.g. {@code A & B}, {@code A Feat. B}.
	 * A list of collaborators ({@code A, B, C}) is cut to the first artist: Last.fm would treat the whole list
	 * as one new artist. Discogs' "Various" becomes "Various Artists", Last.fm's name for compilations.
	 */
	static String joinArtists(List<DiscogsRelease.Artist> artists) {
		if (artists == null || artists.isEmpty()) {
			return "";
		}
		var out = new StringBuilder();
		for (int i = 0; i < artists.size(); i++) {
			var artist = artists.get(i);
			out.append(clean(artist.name()));
			String join = artist.join() == null ? "" : artist.join().trim();
			if (i == artists.size() - 1 || join.isEmpty() || join.equals(",")) {
				break;
			}
			out.append(' ').append(join).append(' ');
		}
		String joined = out.toString();
		return joined.equals("Various") ? "Various Artists" : joined;
	}

}
