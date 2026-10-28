package com.scrobblecue.tracklist;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A parsed Discogs track position.
 *
 * <ul>
 * <li>{@code A1}, {@code B12}, {@code A}, {@code AA}, {@code A1a} – vinyl side (letters), track, sub-track
 * <li>{@code 1-3}, {@code 2.1} – disc and track, no sides
 * <li>{@code 7} – track number only, no sides
 * <li>anything else ({@code ""}, {@code Video}, {@code Bonus}) – unassigned
 * </ul>
 *
 * @param kind which pattern matched
 * @param side side letters for {@link Kind#SIDE}, otherwise {@code null}
 * @param disc disc number for {@link Kind#DISC_TRACK}, otherwise {@code null}
 */
record Position(Kind kind, String side, Integer disc) {

	enum Kind {

		SIDE, DISC_TRACK, NUMBER, UNASSIGNED

	}

	private static final Pattern SIDE = Pattern.compile("^([A-Z]{1,2})(\\d*)([a-z]?)$");

	private static final Pattern DISC_TRACK = Pattern.compile("^(\\d+)[-.](\\d+)$");

	private static final Pattern NUMBER = Pattern.compile("^\\d+$");

	static Position parse(String raw) {
		String position = raw == null ? "" : raw.trim();
		Matcher side = SIDE.matcher(position);
		if (side.matches()) {
			return new Position(Kind.SIDE, side.group(1), null);
		}
		Matcher disc = DISC_TRACK.matcher(position);
		if (disc.matches()) {
			return new Position(Kind.DISC_TRACK, null, Integer.parseInt(disc.group(1)));
		}
		if (NUMBER.matcher(position).matches()) {
			return new Position(Kind.NUMBER, null, null);
		}
		return new Position(Kind.UNASSIGNED, null, null);
	}

}
