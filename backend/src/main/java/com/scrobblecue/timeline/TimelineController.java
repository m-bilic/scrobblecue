package com.scrobblecue.timeline;

import java.time.Instant;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * Timestamp maths for instant and live mode, so every client follows the same rules. Needs no
 * credentials and stores nothing.
 */
@RestController
@RequestMapping("/v1/scrobbles")
class TimelineController {

	private final TimelineCalculator calculator;

	TimelineController(TimelineCalculator calculator) {
		this.calculator = calculator;
	}

	/**
	 * @param position track position
	 * @param durationSec length in seconds
	 * @param scrobblable from tracklist preparation; defaults to {@code true}
	 */
	record TrackInput(@NotNull String position, @Positive int durationSec, Boolean scrobblable) {
	}

	/**
	 * Give exactly one of {@code startedAt} (live mode) or {@code endedAt} (instant mode).
	 */
	record TimelineRequest(@NotEmpty List<@Valid @NotNull TrackInput> tracks, Instant startedAt, Instant endedAt) {

		@JsonIgnore
		@AssertTrue(message = "give exactly one of startedAt or endedAt")
		boolean isExactlyOneTime() {
			return (startedAt == null) != (endedAt == null);
		}

	}

	record TimelineResponse(List<TimelineCalculator.Entry> tracks) {
	}

	@PostMapping("/timeline")
	TimelineResponse timeline(@Valid @RequestBody TimelineRequest request) {
		var inputs = request.tracks()
			.stream()
			.map(t -> new TimelineCalculator.Input(t.position(), t.durationSec(),
					t.scrobblable() == null || t.scrobblable()))
			.toList();
		var entries = request.endedAt() != null ? calculator.endingAt(inputs, request.endedAt())
				: calculator.startingAt(inputs, request.startedAt());
		return new TimelineResponse(entries);
	}

}
