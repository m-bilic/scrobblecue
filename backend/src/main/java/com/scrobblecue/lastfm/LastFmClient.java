package com.scrobblecue.lastfm;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import com.scrobblecue.lastfm.LastFmResponses.ScrobbleEntry;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Calls the Last.fm web service. Nothing else in the backend knows Last.fm's URLs or formats.
 */
@Component
public class LastFmClient {

	/** Last.fm accepts at most this many scrobbles per request. */
	public static final int MAX_BATCH = 50;

	private final RestClient http;

	private final LastFmProperties properties;

	private final JsonMapper jsonMapper;

	LastFmClient(RestClient.Builder builder, LastFmProperties properties, JsonMapper jsonMapper) {
		this.http = builder.baseUrl(properties.apiUrl()).build();
		this.properties = properties;
		this.jsonMapper = jsonMapper;
	}

	/** URL of the page where the user approves ScrobbleCue; Last.fm then redirects to {@code callbackUrl}. */
	public String authorizeUrl(String callbackUrl) {
		return UriComponentsBuilder.fromUriString(properties.authUrl())
			.queryParam("api_key", properties.apiKey())
			.queryParam("cb", callbackUrl)
			.encode()
			.toUriString();
	}

	/** Exchanges the token from the callback for a permanent session. */
	public LastFmSession getSession(String token) {
		Map<String, String> params = new LinkedHashMap<>();
		params.put("method", "auth.getSession");
		params.put("token", token);
		var session = call(params, LastFmResponses.SessionResponse.class).session();
		if (session == null || session.key() == null) {
			throw new LastFmException(0, "Last.fm returned no session");
		}
		return new LastFmSession(session.name(), session.key());
	}

	/** Scrobbles up to {@value #MAX_BATCH} tracks; returns one result per track, in order. */
	public List<ScrobbleResult> scrobble(String sessionKey, List<Scrobble> scrobbles) {
		if (scrobbles.isEmpty() || scrobbles.size() > MAX_BATCH) {
			throw new IllegalArgumentException("1 to " + MAX_BATCH + " scrobbles per call");
		}
		Map<String, String> params = new LinkedHashMap<>();
		params.put("method", "track.scrobble");
		params.put("sk", sessionKey);
		for (int i = 0; i < scrobbles.size(); i++) {
			Scrobble s = scrobbles.get(i);
			params.put("artist[" + i + "]", s.artist());
			params.put("track[" + i + "]", s.track());
			params.put("timestamp[" + i + "]", Long.toString(s.timestamp().getEpochSecond()));
			putIfPresent(params, "album[" + i + "]", s.album());
			putIfPresent(params, "albumArtist[" + i + "]", s.albumArtist());
			putIfPresent(params, "duration[" + i + "]", s.durationSec());
		}
		var response = call(params, LastFmResponses.ScrobbleResponse.class);
		List<ScrobbleEntry> entries = response.scrobbles() == null ? List.of() : response.scrobbles().scrobble();
		if (entries == null || entries.size() != scrobbles.size()) {
			throw new LastFmException(0, "Unexpected scrobble response");
		}
		List<ScrobbleResult> results = new ArrayList<>(entries.size());
		for (ScrobbleEntry entry : entries) {
			results.add(toResult(entry));
		}
		return results;
	}

	/** Shows a track as "now playing" on the user's profile. */
	public void updateNowPlaying(String sessionKey, Scrobble track) {
		Map<String, String> params = new LinkedHashMap<>();
		params.put("method", "track.updateNowPlaying");
		params.put("sk", sessionKey);
		params.put("artist", track.artist());
		params.put("track", track.track());
		putIfPresent(params, "album", track.album());
		putIfPresent(params, "albumArtist", track.albumArtist());
		putIfPresent(params, "duration", track.durationSec());
		call(params, LastFmResponses.Error.class);
	}

	/**
	 * The user's scrobbles between two moments (inclusive), newest first, at most 200. Tracks that are
	 * only "now playing" are left out.
	 */
	public List<RecentScrobble> recentTracks(String user, Instant from, Instant to) {
		Map<String, String> params = new LinkedHashMap<>();
		params.put("method", "user.getRecentTracks");
		params.put("user", user);
		params.put("from", Long.toString(from.getEpochSecond()));
		params.put("to", Long.toString(to.getEpochSecond()));
		params.put("limit", "200");
		var response = call(params, LastFmResponses.RecentTracksResponse.class, false);
		if (response.recenttracks() == null || response.recenttracks().track() == null) {
			return List.of();
		}
		List<RecentScrobble> scrobbles = new ArrayList<>();
		for (var track : response.recenttracks().track()) {
			if (track.date() == null || track.date().uts() == null) {
				continue;
			}
			scrobbles.add(new RecentScrobble(text(track.artist()), track.name(), text(track.album()),
					Instant.ofEpochSecond(Long.parseLong(track.date().uts()))));
		}
		return scrobbles;
	}

	private <T> T call(Map<String, String> params, Class<T> type) {
		return call(params, type, true);
	}

	private <T> T call(Map<String, String> params, Class<T> type, boolean signed) {
		params.put("api_key", properties.apiKey());
		if (signed) {
			params.put("api_sig", LastFmSigner.sign(params, properties.sharedSecret()));
		}
		params.put("format", "json");

		MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
		params.forEach(form::add);

		String body = http.post()
			.contentType(MediaType.APPLICATION_FORM_URLENCODED)
			.body(form)
			.retrieve()
			// Last.fm reports errors in the JSON body, sometimes with a 4xx status; read them below
			.onStatus(status -> true, (request, response) -> {
			})
			.body(String.class);

		try {
			var error = jsonMapper.readValue(body, LastFmResponses.Error.class);
			if (error.error() != null) {
				throw new LastFmException(error.error(), error.message());
			}
			return jsonMapper.readValue(body, type);
		}
		catch (JacksonException | IllegalArgumentException ex) {
			throw new LastFmException(0, "Unreadable response from Last.fm");
		}
	}

	private static ScrobbleResult toResult(ScrobbleEntry entry) {
		var ignored = entry.ignoredMessage();
		int code = ignored == null || ignored.code() == null ? 0 : Integer.parseInt(ignored.code());
		return code == 0 ? ScrobbleResult.accepted() : ScrobbleResult.ignored(code, ignored.text());
	}

	private static String text(LastFmResponses.Text value) {
		return value == null ? null : value.text();
	}

	private static void putIfPresent(Map<String, String> params, String name, Object value) {
		if (value != null && !value.toString().isBlank()) {
			params.put(name, value.toString());
		}
	}

}
