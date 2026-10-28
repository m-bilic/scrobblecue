package com.scrobblecue.tracklist;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The parts of a Discogs release (from {@code GET https://api.discogs.com/releases/{id}}) that tracklist
 * preparation reads. Clients send the release JSON as they got it from Discogs; other fields are ignored.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DiscogsRelease(long id, String title, List<Artist> artists, List<Format> formats,
		List<Track> tracklist) {

	@JsonIgnoreProperties(ignoreUnknown = true)
	@Schema(name = "DiscogsArtist")
	public record Artist(String name, String join) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	@Schema(name = "DiscogsFormat")
	public record Format(String name, String qty, List<String> descriptions) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	@Schema(name = "DiscogsTrack")
	public record Track(String position, @JsonProperty("type_") String type, String title, String duration,
			List<Artist> artists, @JsonProperty("sub_tracks") List<Track> subTracks) {
	}

}
