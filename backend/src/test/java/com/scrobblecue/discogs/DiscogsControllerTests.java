package com.scrobblecue.discogs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import java.time.Instant;

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
 * Discogs connect flow and collection forwarding, with the Discogs client replaced by a mock.
 */
@SpringBootTest
@AutoConfigureMockMvc
class DiscogsControllerTests {

	private static final Instant NOW = Instant.parse("2026-10-27T20:00:00Z");

	@Autowired
	MockMvcTester mvc;

	@Autowired
	CredentialService credentials;

	@MockitoBean
	DiscogsClient discogs;

	@Test
	void startNeedsLastFmCredentials() {
		var result = mvc.post().uri("/v1/auth/discogs/start")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"client\":\"WEB\"}")
			.exchange();

		assertThat(result).hasStatus(HttpStatus.UNAUTHORIZED);
	}

	@Test
	void startReturnsOurRedirectUrlWithState() {
		var result = mvc.post().uri("/v1/auth/discogs/start")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + lastFmOnly())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"client\":\"ANDROID\"}")
			.exchange();

		assertThat(result).hasStatusOk();
		assertThat(result).bodyJson()
			.extractingPath("$.authorizeUrl")
			.asString()
			.startsWith("http://localhost:8080/v1/auth/discogs/redirect?state=test1.");
	}

	@Test
	void redirectKeepsRequestSecretInCookieAndSendsUserToDiscogs() {
		String state = credentials.newState("discogs", ClientType.WEB, lastFmOnly());
		given(discogs.requestToken(anyString())).willReturn(new DiscogsClient.TokenPair("rt", "rts"));
		given(discogs.authorizeUrl("rt")).willReturn("https://www.discogs.com/oauth/authorize?oauth_token=rt");

		var result = mvc.get().uri("/v1/auth/discogs/redirect?state={state}", state).exchange();

		assertThat(result).hasStatus(HttpStatus.FOUND)
			.hasHeader(HttpHeaders.LOCATION, "https://www.discogs.com/oauth/authorize?oauth_token=rt");
		String cookie = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
		assertThat(cookie).startsWith("sc_discogs_request=test1.")
			.contains("HttpOnly", "Secure", "SameSite=Lax", "Path=/v1/auth/discogs")
			.doesNotContain("rts");
		then(discogs).should().requestToken("http://localhost:8080/v1/auth/discogs/callback?state=" + state);
	}

	@Test
	void callbackAddsDiscogsToTheExistingBundle() {
		String state = credentials.newState("discogs", ClientType.ANDROID, lastFmOnly());
		given(discogs.accessToken("rt", "rts", "v")).willReturn(new DiscogsClient.TokenPair("at", "ats"));
		given(discogs.identity("at", "ats")).willReturn("vinylfan");

		var result = mvc.get()
			.uri("/v1/auth/discogs/callback?state={state}&oauth_token=rt&oauth_verifier=v", state)
			.cookie(new Cookie("sc_discogs_request", credentials.sealRequestSecret("rts")))
			.exchange();

		assertThat(result).hasStatus(HttpStatus.FOUND);
		var location = UriComponentsBuilder.fromUriString(result.getResponse().getHeader(HttpHeaders.LOCATION)).build();
		var bundle = credentials.open(location.getFragment().substring("bundle=".length()));
		assertThat(bundle.lastfm().user()).isEqualTo("marin");
		assertThat(bundle.discogs()).isEqualTo(new CredentialBundle.Discogs("vinylfan", "at", "ats"));
		assertThat(result.getResponse().getHeaders(HttpHeaders.SET_COOKIE))
			.anySatisfy(c -> assertThat(c).startsWith("sc_discogs_request=;").contains("Max-Age=0"));
	}

	@Test
	void callbackWithoutRequestSecretReportsExpired() {
		String state = credentials.newState("discogs", ClientType.WEB, lastFmOnly());

		var result = mvc.get().uri("/v1/auth/discogs/callback?state={state}&oauth_token=rt&oauth_verifier=v", state)
			.exchange();

		assertThat(result).hasStatus(HttpStatus.FOUND)
			.hasHeader(HttpHeaders.LOCATION, "http://localhost:5173?authError=discogs-expired");
	}

	@Test
	void collectionWithoutDiscogsIsAConflict() {
		var result = mvc.get().uri("/v1/discogs/collection")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + lastFmOnly())
			.exchange();

		assertThat(result).hasStatus(HttpStatus.CONFLICT);
		assertThat(result).bodyJson()
			.extractingPath("$.type")
			.isEqualTo("https://scrobblecue.app/errors/discogs-not-connected");
	}

	@Test
	void collectionPageIsPassedThrough() {
		given(discogs.collectionPage(any(), anyInt()))
			.willReturn(new DiscogsClient.ForwardedResponse("{\"pagination\":{\"page\":1},\"releases\":[]}"));

		var result = mvc.get().uri("/v1/discogs/collection?page=1")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + withDiscogs())
			.exchange();

		assertThat(result).hasStatusOk();
		assertThat(result).bodyJson().extractingPath("$.pagination.page").isEqualTo(1);
		then(discogs).should().collectionPage(new CredentialBundle.Discogs("vinylfan", "at", "ats"), 1);
	}

	@Test
	void rateLimitIsReportedWithRetryAfter() {
		given(discogs.collectionPage(any(), anyInt())).willThrow(new DiscogsException(429, "limit", 12L));

		var result = mvc.get().uri("/v1/discogs/collection")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + withDiscogs())
			.exchange();

		assertThat(result).hasStatus(HttpStatus.TOO_MANY_REQUESTS).hasHeader(HttpHeaders.RETRY_AFTER, "12");
	}

	@Test
	void disconnectReturnsBundleWithoutDiscogs() throws Exception {
		var result = mvc.post().uri("/v1/auth/discogs/disconnect")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + withDiscogs())
			.exchange();

		assertThat(result).hasStatusOk();
		String bundle = com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.bundle");
		assertThat(credentials.open(bundle).discogs()).isNull();
	}

	@Test
	void disconnectOnWebsiteReplacesTheCookie() {
		var result = mvc.post().uri("/v1/auth/discogs/disconnect")
			.cookie(new Cookie("sc_credentials", withDiscogs()))
			.header("X-ScrobbleCue", "1")
			.exchange();

		assertThat(result).hasStatus(HttpStatus.NO_CONTENT);
		String cookie = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
		String value = cookie.substring("sc_credentials=".length(), cookie.indexOf(';'));
		assertThat(credentials.open(value).discogs()).isNull();
	}

	private String lastFmOnly() {
		return credentials.seal(CredentialBundle.ofLastFm("marin", "sk", NOW));
	}

	private String withDiscogs() {
		return credentials.seal(CredentialBundle.ofLastFm("marin", "sk", NOW)
			.withDiscogs(new CredentialBundle.Discogs("vinylfan", "at", "ats"), NOW));
	}

}
