package com.scrobblecue.credentials;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import tools.jackson.databind.json.JsonMapper;

/**
 * Users who approve access long after starting are sent back to their client with an error, not shown
 * a bare error response.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ExpiredStateTests {

	@Autowired
	MockMvcTester mvc;

	@Autowired
	Sealer sealer;

	@Autowired
	JsonMapper jsonMapper;

	/** States issued 8 hours ago with the application's own keys: genuine but expired. */
	private CredentialService earlier() {
		return new CredentialService(sealer, jsonMapper, Clock.offset(Clock.systemUTC(), Duration.ofHours(-8)));
	}

	@Test
	void lastFmCallbackRedirectsWebsiteWithExpiredError() {
		String state = earlier().newState("lastfm", ClientType.WEB);

		var result = mvc.get().uri("/v1/auth/lastfm/callback?state={state}&token=tok", state).exchange();

		assertThat(result).hasStatus(HttpStatus.FOUND)
			.hasHeader(HttpHeaders.LOCATION, "http://localhost:5173?authError=lastfm-expired");
	}

	@Test
	void discogsRedirectSendsAppBackWithExpiredError() {
		String state = earlier().newState("discogs", ClientType.ANDROID);

		var result = mvc.get().uri("/v1/auth/discogs/redirect?state={state}", state).exchange();

		assertThat(result).hasStatus(HttpStatus.FOUND);
		assertThat(result.getResponse().getHeader(HttpHeaders.LOCATION)).endsWith("#error=discogs-expired");
	}

}
