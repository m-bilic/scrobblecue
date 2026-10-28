package com.scrobblecue.lastfm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.json.JsonMapper;

/**
 * Tests the Last.fm client against canned responses; Last.fm itself is never called.
 */
class LastFmClientTests {

	private static final String API_URL = "https://ws.audioscrobbler.com/2.0/";

	private MockRestServiceServer server;

	private LastFmClient client;

	@BeforeEach
	void setUp() {
		RestClient.Builder builder = RestClient.builder();
		server = MockRestServiceServer.bindTo(builder).build();
		var properties = new LastFmProperties("key", "secret", API_URL, "https://www.last.fm/api/auth/");
		client = new LastFmClient(builder, properties, JsonMapper.builder().build());
	}

	@Test
	void authorizeUrlContainsApiKeyAndEncodedCallback() {
		String url = client.authorizeUrl("http://localhost:8080/v1/auth/lastfm/callback?state=k1.abc");

		assertThat(url).isEqualTo("https://www.last.fm/api/auth/?api_key=key"
				+ "&cb=http://localhost:8080/v1/auth/lastfm/callback?state%3Dk1.abc");
	}

	@Test
	void getSessionReturnsUserAndKey() {
		server.expect(requestTo(API_URL))
			.andExpect(method(HttpMethod.POST))
			.andExpect(content().formDataContains(form("method", "auth.getSession", "token", "tok", "format", "json")))
			.andRespond(withSuccess("""
					{"session":{"name":"marin","key":"sk-123","subscriber":0}}""", MediaType.APPLICATION_JSON));

		var session = client.getSession("tok");

		assertThat(session.user()).isEqualTo("marin");
		assertThat(session.key()).isEqualTo("sk-123");
		server.verify();
	}

	@Test
	void singleScrobbleResponseIsAnObject() {
		server.expect(requestTo(API_URL))
			.andExpect(content().formDataContains(form("method", "track.scrobble", "sk", "sk-123", "artist[0]",
					"Rick Astley", "track[0]", "Never Gonna Give You Up", "timestamp[0]", "1793041107",
					"duration[0]", "213")))
			.andRespond(withSuccess("""
					{"scrobbles":{"@attr":{"accepted":1,"ignored":0},
					  "scrobble":{"artist":{"corrected":"0","#text":"Rick Astley"},
					              "ignoredMessage":{"code":"0","#text":""}}}}""", MediaType.APPLICATION_JSON));

		var results = client.scrobble("sk-123", List.of(new Scrobble("Rick Astley", "Never Gonna Give You Up", null,
				null, 213, Instant.ofEpochSecond(1793041107L))));

		assertThat(results).containsExactly(ScrobbleResult.accepted());
	}

	@Test
	void severalScrobblesResponseIsAnArrayWithIgnoredEntries() {
		server.expect(requestTo(API_URL))
			.andRespond(withSuccess("""
					{"scrobbles":{"@attr":{"accepted":1,"ignored":1},"scrobble":[
					  {"ignoredMessage":{"code":"0","#text":""}},
					  {"ignoredMessage":{"code":"3","#text":"Timestamp too old"}}]}}""", MediaType.APPLICATION_JSON));

		var results = client.scrobble("sk-123", List.of(
				new Scrobble("A", "One", "Album", null, null, Instant.ofEpochSecond(1793041000L)),
				new Scrobble("A", "Two", "Album", null, null, Instant.ofEpochSecond(1793041200L))));

		assertThat(results).containsExactly(ScrobbleResult.accepted(), ScrobbleResult.ignored(3, "Timestamp too old"));
	}

	@Test
	void errorInBodyBecomesLastFmException() {
		server.expect(requestTo(API_URL))
			.andRespond(withStatus(HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_JSON).body("""
					{"error":9,"message":"Invalid session key - Please re-authenticate"}"""));

		assertThatExceptionOfType(LastFmException.class)
			.isThrownBy(() -> client.updateNowPlaying("sk-old", new Scrobble("A", "B", null, null, null, null)))
			.satisfies(ex -> assertThat(ex.code()).isEqualTo(LastFmException.INVALID_SESSION_KEY));
	}

	@Test
	void recentTracksSkipNowPlayingAndAreNotSigned() {
		server.expect(requestTo(API_URL))
			.andExpect(content().formDataContains(form("method", "user.getRecentTracks", "user", "marin", "from",
					"1793040000", "to", "1793043600")))
			.andExpect(request -> assertThat(request.getBody().toString()).doesNotContain("api_sig"))
			.andRespond(withSuccess("""
					{"recenttracks":{"track":[
					  {"artist":{"#text":"Band"},"name":"Live","album":{"#text":"LP"},"@attr":{"nowplaying":"true"}},
					  {"artist":{"#text":"Band"},"name":"Done","album":{"#text":"LP"},"date":{"uts":"1793041107"}}]}}""",
					MediaType.APPLICATION_JSON));

		var recent = client.recentTracks("marin", Instant.ofEpochSecond(1793040000L), Instant.ofEpochSecond(1793043600L));

		assertThat(recent).containsExactly(new RecentScrobble("Band", "Done", "LP", Instant.ofEpochSecond(1793041107L)));
	}

	private static Map<String, String> form(String... pairs) {
		Map<String, String> map = new LinkedHashMap<>();
		for (int i = 0; i < pairs.length; i += 2) {
			map.put(pairs[i], pairs[i + 1]);
		}
		return map;
	}

}
