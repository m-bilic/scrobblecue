package com.scrobblecue.timeline;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Computes when each track of a side (or selection) started and ended – the timestamps Last.fm
 * receives, and the moments live mode sends updates. Stateless.
 */
@Component
public class TimelineCalculator {

	/** Last.fm does not count tracks of 30 seconds or less. */
	static final int MIN_SCROBBLE_SECONDS = 31;

	/** One track to place on the timeline, in play order. */
	@Schema(hidden = true)
	public record Input(String position, int durationSec, boolean scrobblable) {
	}

	/**
	 * @param position track position
	 * @param startsAt when the track starts – the scrobble timestamp, and when live mode sends "now playing"
	 * @param endsAt when the track ends – when live mode sends the scrobble
	 * @param scrobble whether to scrobble it at all (long enough, not skipped)
	 */
	@Schema(name = "TimelineEntry")
	public record Entry(String position, Instant startsAt, Instant endsAt, boolean scrobble) {
	}

	/** Instant mode: the last track ended at {@code endedAt}; everything before is counted backwards. */
	public List<Entry> endingAt(List<Input> tracks, Instant endedAt) {
		long total = tracks.stream().mapToLong(Input::durationSec).sum();
		return startingAt(tracks, endedAt.minus(Duration.ofSeconds(total)));
	}

	/** Live mode: the first track started at {@code startedAt}; each next track starts when the previous ends. */
	public List<Entry> startingAt(List<Input> tracks, Instant startedAt) {
		List<Entry> entries = new ArrayList<>(tracks.size());
		Instant start = startedAt;
		for (Input track : tracks) {
			Instant end = start.plusSeconds(track.durationSec());
			entries.add(new Entry(track.position(), start, end,
					track.scrobblable() && track.durationSec() >= MIN_SCROBBLE_SECONDS));
			start = end;
		}
		return entries;
	}

}
