package com.scrobblecue.timeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.scrobblecue.timeline.TimelineCalculator.Entry;
import com.scrobblecue.timeline.TimelineCalculator.Input;

class TimelineCalculatorTests {

	private final TimelineCalculator calculator = new TimelineCalculator();

	private final List<Input> side = List.of(new Input("A1", 200, true), new Input("A2", 20, true),
			new Input("A3", 100, true));

	@Test
	void instantModeEndsTheLastTrackAtEndedAt() {
		var entries = calculator.endingAt(side, Instant.parse("2026-10-27T20:00:00Z"));

		assertThat(entries).containsExactly(
				new Entry("A1", Instant.parse("2026-10-27T19:54:40Z"), Instant.parse("2026-10-27T19:58:00Z"), true),
				new Entry("A2", Instant.parse("2026-10-27T19:58:00Z"), Instant.parse("2026-10-27T19:58:20Z"), false),
				new Entry("A3", Instant.parse("2026-10-27T19:58:20Z"), Instant.parse("2026-10-27T20:00:00Z"), true));
	}

	@Test
	void liveModeStartsTheFirstTrackAtStartedAt() {
		var entries = calculator.startingAt(side, Instant.parse("2026-10-27T20:00:00Z"));

		assertThat(entries).extracting(Entry::startsAt)
			.containsExactly(Instant.parse("2026-10-27T20:00:00Z"), Instant.parse("2026-10-27T20:03:20Z"),
					Instant.parse("2026-10-27T20:03:40Z"));
	}

	@Test
	void skippedTracksKeepTheirTimeButAreNotScrobbled() {
		var entries = calculator.startingAt(List.of(new Input("A1", 200, false)), Instant.EPOCH);

		assertThat(entries.get(0).scrobble()).isFalse();
		assertThat(entries.get(0).endsAt()).isEqualTo(Instant.EPOCH.plusSeconds(200));
	}

}
