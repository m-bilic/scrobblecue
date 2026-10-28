package com.scrobblecue.tracklist;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses Discogs durations ({@code 3:32}, {@code 1:02:15}) and holds the fallback length.
 */
final class Durations {

	/** Used when no source knows a track's length (D16): 3:30. */
	static final int DEFAULT_SECONDS = 210;

	private static final Pattern DURATION = Pattern.compile("^(?:(\\d+):)?(\\d{1,3}):(\\d{2})$");

	private Durations() {
	}

	/** Seconds, or {@code null} when the value is empty or not a duration. */
	static Integer parse(String value) {
		if (value == null) {
			return null;
		}
		Matcher m = DURATION.matcher(value.trim());
		if (!m.matches()) {
			return null;
		}
		int hours = m.group(1) == null ? 0 : Integer.parseInt(m.group(1));
		int seconds = hours * 3600 + Integer.parseInt(m.group(2)) * 60 + Integer.parseInt(m.group(3));
		return seconds > 0 ? seconds : null;
	}

}
