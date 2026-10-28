package com.scrobblecue.lastfm;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Shapes of Last.fm's JSON responses – only the fields ScrobbleCue reads.
 */
final class LastFmResponses {

	private LastFmResponses() {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record Error(Integer error, String message) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record SessionResponse(Session session) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record Session(String name, String key) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record ScrobbleResponse(Scrobbles scrobbles) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record Scrobbles(
			// Last.fm returns an object for one scrobble and an array for several
			@JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY) List<ScrobbleEntry> scrobble) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record ScrobbleEntry(IgnoredMessage ignoredMessage) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record IgnoredMessage(String code, @JsonProperty("#text") String text) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record RecentTracksResponse(RecentTracks recenttracks) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record RecentTracks(@JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY) List<RecentTrack> track) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record RecentTrack(Text artist, String name, Text album, RecentDate date) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record Text(@JsonProperty("#text") String text) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	record RecentDate(String uts) {
	}

}
