package com.scrobblecue.lastfm;

import java.time.Instant;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.scrobblecue.credentials.CredentialBundle;

/**
 * Sends scrobbles and "now playing" updates for the user in the credential bundle. Nothing is stored:
 * the client keeps its own queue and retries.
 */
@RestController
@RequestMapping("/v1/lastfm")
class LastFmScrobbleController {

	private final LastFmClient lastFm;

	LastFmScrobbleController(LastFmClient lastFm) {
		this.lastFm = lastFm;
	}

	record ScrobbleItem(@NotBlank String artist, @NotBlank String track, String album, String albumArtist,
			@Positive Integer durationSec, @NotNull Instant timestamp) {

		Scrobble toScrobble() {
			return new Scrobble(artist, track, album, albumArtist, durationSec, timestamp);
		}

	}

	record ScrobbleRequest(@NotEmpty @Size(max = LastFmClient.MAX_BATCH) List<@Valid @NotNull ScrobbleItem> scrobbles) {
	}

	record ScrobbleResponse(List<ScrobbleResult> results) {
	}

	record NowPlayingRequest(@NotBlank String artist, @NotBlank String track, String album, String albumArtist,
			@Positive Integer durationSec) {
	}

	/** Scrobbles up to 50 tracks. Results are returned in the same order as the request. */
	@PostMapping("/scrobbles")
	ScrobbleResponse scrobble(@Valid @RequestBody ScrobbleRequest request, CredentialBundle credentials) {
		var scrobbles = request.scrobbles().stream().map(ScrobbleItem::toScrobble).toList();
		return new ScrobbleResponse(lastFm.scrobble(credentials.lastfm().sessionKey(), scrobbles));
	}

	record RecentResponse(List<RecentScrobble> scrobbles) {
	}

	/**
	 * The user's scrobbles in a time range. Clients use it before retrying a send whose outcome is unknown,
	 * so nothing is scrobbled twice (SPEC.md D10).
	 */
	@GetMapping("/recent")
	RecentResponse recent(@RequestParam Instant from, @RequestParam Instant to, CredentialBundle credentials) {
		return new RecentResponse(lastFm.recentTracks(credentials.lastfm().user(), from, to));
	}

	/** Shows a track as "now playing" on the user's Last.fm profile. */
	@PostMapping("/now-playing")
	ResponseEntity<Void> nowPlaying(@Valid @RequestBody NowPlayingRequest request, CredentialBundle credentials) {
		lastFm.updateNowPlaying(credentials.lastfm().sessionKey(), new Scrobble(request.artist(), request.track(),
				request.album(), request.albumArtist(), request.durationSec(), null));
		return ResponseEntity.noContent().build();
	}

}
