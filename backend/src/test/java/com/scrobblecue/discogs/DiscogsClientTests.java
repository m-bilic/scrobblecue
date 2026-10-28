package com.scrobblecue.discogs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.scrobblecue.credentials.CredentialBundle;

import tools.jackson.databind.json.JsonMapper;

/**
 * Tests the Discogs client against canned responses; Discogs itself is never called.
 */
class DiscogsClientTests {

	private static final String API = "https://api.discogs.com";

	private final RestClient.Builder builder = RestClient.builder();

	private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

	private DiscogsClient client(int requestsPerMinute) {
		var properties = new DiscogsProperties("ck", "cs&x", "ScrobbleCueTests/0.1", API,
				"https://www.discogs.com/oauth/authorize", requestsPerMinute);
		return new DiscogsClient(builder, properties, JsonMapper.builder().build(),
				Clock.fixed(Instant.parse("2026-10-27T20:00:00Z"), ZoneOffset.UTC));
	}

	@Test
	void requestTokenSendsPlaintextSignatureCallbackAndUserAgent() {
		var client = client(50);
		server.expect(requestTo(API + "/oauth/request_token"))
			.andExpect(method(HttpMethod.GET))
			.andExpect(header(HttpHeaders.USER_AGENT, "ScrobbleCueTests/0.1"))
			.andExpect(header(HttpHeaders.AUTHORIZATION, Matchers.allOf(Matchers.startsWith("OAuth "),
					Matchers.containsString("oauth_consumer_key=\"ck\""),
					// consumerSecret "cs&x" encoded, then "&" and an empty token secret, encoded again in the header
					Matchers.containsString("oauth_signature=\"cs%2526x%26\""),
					Matchers.containsString("oauth_signature_method=\"PLAINTEXT\""),
					Matchers.containsString("oauth_timestamp=\"1793131200\""),
					Matchers.containsString("oauth_callback=\"http%3A%2F%2Flocalhost%3A8080%2Fcb%3Fstate%3Dabc\""))))
			.andRespond(withSuccess("oauth_token=rt&oauth_token_secret=rts&oauth_callback_confirmed=true",
					MediaType.APPLICATION_FORM_URLENCODED));

		var token = client.requestToken("http://localhost:8080/cb?state=abc");

		assertThat(token.token()).isEqualTo("rt");
		assertThat(token.secret()).isEqualTo("rts");
		assertThat(client.authorizeUrl("rt")).isEqualTo("https://www.discogs.com/oauth/authorize?oauth_token=rt");
		server.verify();
	}

	@Test
	void accessTokenIsSignedWithTheRequestTokenSecret() {
		var client = client(50);
		server.expect(requestTo(API + "/oauth/access_token"))
			.andExpect(method(HttpMethod.POST))
			.andExpect(header(HttpHeaders.AUTHORIZATION, Matchers.allOf(
					Matchers.containsString("oauth_signature=\"cs%2526x%26rts\""),
					Matchers.containsString("oauth_token=\"rt\""), Matchers.containsString("oauth_verifier=\"v\""))))
			.andRespond(withSuccess("oauth_token=at&oauth_token_secret=ats", MediaType.APPLICATION_FORM_URLENCODED));

		var token = client.accessToken("rt", "rts", "v");

		assertThat(token).extracting(DiscogsClient.TokenPair::token, DiscogsClient.TokenPair::secret)
			.containsExactly("at", "ats");
	}

	@Test
	void identityReturnsUsername() {
		var client = client(50);
		server.expect(requestTo(API + "/oauth/identity"))
			.andRespond(withSuccess("{\"id\":1,\"username\":\"vinylfan\",\"consumer_name\":\"ScrobbleCue\"}",
					MediaType.APPLICATION_JSON));

		assertThat(client.identity("at", "ats")).isEqualTo("vinylfan");
	}

	@Test
	void collectionPageIsForwardedUnchanged() {
		var client = client(50);
		String body = "{\"pagination\":{\"page\":2,\"pages\":3},\"releases\":[{\"id\":1}]}";
		server.expect(requestTo(API + "/users/vinylfan/collection/folders/0/releases?page=2&per_page=100"))
			.andExpect(header(HttpHeaders.AUTHORIZATION, Matchers.containsString("oauth_token=\"at\"")))
			.andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

		var page = client.collectionPage(new CredentialBundle.Discogs("vinylfan", "at", "ats"), 2);

		assertThat(page.body()).isEqualTo(body);
	}

	@Test
	void unauthorizedBecomesDiscogsException() {
		var client = client(50);
		server.expect(requestTo(API + "/oauth/identity")).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

		assertThatExceptionOfType(DiscogsException.class).isThrownBy(() -> client.identity("at", "ats"))
			.satisfies(ex -> assertThat(ex.status()).isEqualTo(401));
	}

	@Test
	void ownRateLimitStopsRequestsBeforeTheyReachDiscogs() {
		var client = client(1);
		server.expect(requestTo(API + "/oauth/identity"))
			.andRespond(withSuccess("{\"username\":\"vinylfan\"}", MediaType.APPLICATION_JSON));

		client.identity("at", "ats");

		assertThatExceptionOfType(DiscogsException.class).isThrownBy(() -> client.identity("at", "ats"))
			.satisfies(ex -> {
				assertThat(ex.status()).isEqualTo(429);
				assertThat(ex.retryAfterSeconds()).isPositive();
			});
		server.verify();
	}

}
