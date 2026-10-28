package com.scrobblecue.tracklist;

import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Prepares a tracklist the client fetched from Discogs itself. Needs no credentials and stores nothing.
 */
@RestController
@RequestMapping("/v1/tracklists")
class TracklistController {

	private final TracklistPreparer preparer;

	TracklistController(TracklistPreparer preparer) {
		this.preparer = preparer;
	}

	/**
	 * @param release raw Discogs release JSON
	 * @param durations lengths in seconds the client looked up elsewhere, by position (optional)
	 * @param corrections the user's corrections for this release (optional)
	 */
	record PrepareRequest(@NotNull DiscogsRelease release, Map<String, Integer> durations,
			List<@Valid @NotNull TrackCorrection> corrections) {
	}

	@PostMapping("/prepare")
	PreparedTracklist prepare(@Valid @RequestBody PrepareRequest request) {
		return preparer.prepare(request.release(), request.durations(), request.corrections());
	}

}
