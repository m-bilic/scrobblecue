package com.scrobblecue.lastfm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.web.util.UriComponentsBuilder;

import com.scrobblecue.credentials.ClientType;
import com.scrobblecue.credentials.CredentialBundle;
import com.scrobblecue.credentials.CredentialService;

import jakarta.servlet.http.Cookie;

/**
 * Connect flow and scrobble endpoints, with the Last.fm client replaced by a mock.
 */
@SpringBootTest
@AutoConfigureMockMvc
class LastFmControllerTests {

	@Autowired
	MockMvcTester mvc;

	@Autowired
	CredentialService credentials;

	@MockitoBean
	LastFmClient lastFm;

	@Test
	void startReturnsLastFmUrlWithSealedStateInCallback() {
		given(lastFm.authorizeUrl(anyString())).willAnswer(call -> "https://www.last.fm/api/auth/?cb=" + call.getArgument(0));

		var result = mvc.post().uri("/v1/auth/lastfm/start")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"client\":\"ANDROID\"}")
			.exchange();

		assertThat(result).hasStatusOk();
		assertThat(result).bodyJson()
			.extractingPath("$.authorizeUrl")
			.asString()
			.startsWith("https://www.last.fm/api/auth/?cb=http://localhost:8080/v1/auth/lastfm/callback?state=test1.");
	}

	@Test
	void callbackForWebSetsHttpOnlyCookieAndRedirects() {
		given(lastFm.getSession("tok")).willReturn(new LastFmSession("marin", "sk-123"));
		String state = credentials.newState("lastfm", ClientType.WEB);

		var result = mvc.get().uri("/v1/auth/lastfm/callback?state={state}&token=tok", state).exchange();

		assertThat(result).hasStatus(HttpStatus.FOUND).hasHeader(HttpHeaders.LOCATION, "http://localhost:5173");
		String cookie = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
		assertThat(cookie).startsWith("sc_credentials=test1.")
			.contains("HttpOnly", "Secure", "SameSite=Strict", "Path=/v1")
			.doesNotContain("sk-123");
	}

	@Test
	void callbackForAndroidPutsBundleInLinkFragment() {
		given(lastFm.getSession("tok")).willReturn(new LastFmSession("marin", "sk-123"));
		String state = credentials.newState("lastfm", ClientType.ANDROID);

		var result = mvc.get().uri("/v1/auth/lastfm/callback?state={state}&token=tok", state).exchange();

		assertThat(result).hasStatus(HttpStatus.FOUND);
		var location = UriComponentsBuilder.fromUriString(result.getResponse().getHeader(HttpHeaders.LOCATION)).build();
		assertThat(location.getHost()).isEqualTo("scrobblecue.app");
		assertThat(location.getFragment()).startsWith("bundle=");
		var bundle = credentials.open(location.getFragment().substring("bundle=".length()));
		assertThat(bundle.lastfm().user()).isEqualTo("marin");
		assertThat(bundle.lastfm().sessionKey()).isEqualTo("sk-123");
	}

	@Test
	void callbackWithoutTokenReportsDenied() {
		String state = credentials.newState("lastfm", ClientType.WEB);

		var result = mvc.get().uri("/v1/auth/lastfm/callback?state={state}", state).exchange();

		assertThat(result).hasStatus(HttpStatus.FOUND)
			.hasHeader(HttpHeaders.LOCATION, "http://localhost:5173?authError=lastfm-denied");
		then(lastFm).should(never()).getSession(anyString());
	}

	@Test
	void callbackWithForgedStateIsRejected() {
		var result = mvc.get().uri("/v1/auth/lastfm/callback?state=test1.forged&token=tok").exchange();

		assertThat(result).hasStatus(HttpStatus.UNAUTHORIZED);
		then(lastFm).should(never()).getSession(anyString());
	}

	@Test
	void scrobbleWithoutCredentialsIsUnauthorized() {
		var result = mvc.post().uri("/v1/lastfm/scrobbles")
			.contentType(MediaType.APPLICATION_JSON)
			.content(scrobbleJson(1))
			.exchange();

		assertThat(result).hasStatus(HttpStatus.UNAUTHORIZED);
		assertThat(result).bodyJson()
			.extractingPath("$.type")
			.isEqualTo("https://scrobblecue.app/errors/credentials-missing");
	}

	@Test
	void scrobbleWithBearerBundleUsesTheSessionKeyFromTheBundle() {
		given(lastFm.scrobble(eq("sk-123"), anyList())).willReturn(List.of(ScrobbleResult.accepted()));

		var result = mvc.post().uri("/v1/lastfm/scrobbles")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + sealedBundle())
			.contentType(MediaType.APPLICATION_JSON)
			.content(scrobbleJson(1))
			.exchange();

		assertThat(result).hasStatusOk();
		assertThat(result).bodyJson().extractingPath("$.results[0].status").isEqualTo("ACCEPTED");
	}

	@Test
	void scrobbleWithCookieBundleNeedsTheCsrfHeader() {
		given(lastFm.scrobble(eq("sk-123"), anyList())).willReturn(List.of(ScrobbleResult.accepted()));

		var withHeader = mvc.post().uri("/v1/lastfm/scrobbles")
			.cookie(new Cookie("sc_credentials", sealedBundle()))
			.header("X-ScrobbleCue", "1")
			.contentType(MediaType.APPLICATION_JSON)
			.content(scrobbleJson(1))
			.exchange();
		var withoutHeader = mvc.post().uri("/v1/lastfm/scrobbles")
			.cookie(new Cookie("sc_credentials", sealedBundle()))
			.contentType(MediaType.APPLICATION_JSON)
			.content(scrobbleJson(1))
			.exchange();

		assertThat(withHeader).hasStatusOk();
		assertThat(withoutHeader).hasStatus(HttpStatus.FORBIDDEN);
		assertThat(withoutHeader).bodyJson()
			.extractingPath("$.type")
			.isEqualTo("https://scrobblecue.app/errors/csrf-header-missing");
	}

	@Test
	void meTellsTheWebsiteWhoIsConnected() {
		var result = mvc.get().uri("/v1/me").cookie(new Cookie("sc_credentials", sealedBundle())).exchange();

		assertThat(result).hasStatusOk();
		assertThat(result).bodyJson().extractingPath("$.lastfmUser").isEqualTo("marin");
		assertThat(result).bodyJson().extractingPath("$.discogsUser").isNull();
	}

	@Test
	void recentScrobblesComeFromTheBundleUser() {
		given(lastFm.recentTracks(eq("marin"), any(), any())).willReturn(List.of(new RecentScrobble("Rick Astley",
				"Never Gonna Give You Up", null, Instant.parse("2026-10-27T19:38:27Z"))));

		var result = mvc.get()
			.uri("/v1/lastfm/recent?from=2026-10-27T19:00:00Z&to=2026-10-27T20:00:00Z")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + sealedBundle())
			.exchange();

		assertThat(result).hasStatusOk();
		assertThat(result).bodyJson().extractingPath("$.scrobbles[0].timestamp").isEqualTo("2026-10-27T19:38:27Z");
	}

	@Test
	void websiteOriginMayCallTheApiWithCookies() {
		var result = mvc.options().uri("/v1/lastfm/scrobbles")
			.header(HttpHeaders.ORIGIN, "http://localhost:5173")
			.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
			.header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "content-type,x-scrobblecue")
			.exchange();
		var otherSite = mvc.options().uri("/v1/lastfm/scrobbles")
			.header(HttpHeaders.ORIGIN, "https://evil.example")
			.header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
			.exchange();

		assertThat(result).hasStatusOk()
			.hasHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:5173")
			.hasHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true");
		assertThat(otherSite).hasStatus(HttpStatus.FORBIDDEN);
	}

	@Test
	void moreThanFiftyScrobblesAreRejected() {
		var result = mvc.post().uri("/v1/lastfm/scrobbles")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + sealedBundle())
			.contentType(MediaType.APPLICATION_JSON)
			.content(scrobbleJson(51))
			.exchange();

		assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
		then(lastFm).should(never()).scrobble(anyString(), anyList());
	}

	@Test
	void revokedSessionBecomesProblemWithStableType() {
		given(lastFm.scrobble(eq("sk-123"), anyList()))
			.willThrow(new LastFmException(LastFmException.INVALID_SESSION_KEY, "Invalid session key"));

		var result = mvc.post().uri("/v1/lastfm/scrobbles")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + sealedBundle())
			.contentType(MediaType.APPLICATION_JSON)
			.content(scrobbleJson(1))
			.exchange();

		assertThat(result).hasStatus(HttpStatus.UNAUTHORIZED);
		assertThat(result).bodyJson()
			.extractingPath("$.type")
			.isEqualTo("https://scrobblecue.app/errors/lastfm-session-revoked");
	}

	private String sealedBundle() {
		return credentials.seal(CredentialBundle.ofLastFm("marin", "sk-123", Instant.parse("2026-10-27T19:00:00Z")));
	}

	private static String scrobbleJson(int count) {
		String item = """
				{"artist":"Rick Astley","track":"Never Gonna Give You Up","durationSec":213,
				 "timestamp":"2026-10-27T19:38:27Z"}""";
		return "{\"scrobbles\":[" + String.join(",", Collections.nCopies(count, item)) + "]}";
	}

}
