package com.scrobblecue.lastfm;

import java.time.Clock;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import io.swagger.v3.oas.annotations.Operation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import com.scrobblecue.common.UrlProperties;
import com.scrobblecue.credentials.ClientType;
import com.scrobblecue.credentials.CredentialBundle;
import com.scrobblecue.credentials.CredentialDelivery;
import com.scrobblecue.credentials.CredentialService;
import com.scrobblecue.credentials.OAuthState;

/**
 * Connecting Last.fm. This creates the user's credential bundle – there is no ScrobbleCue account.
 */
@RestController
@RequestMapping("/v1/auth")
class LastFmAuthController {

	private static final Logger log = LoggerFactory.getLogger(LastFmAuthController.class);

	private static final String PROVIDER = "lastfm";

	private final LastFmClient lastFm;

	private final CredentialService credentials;

	private final CredentialDelivery delivery;

	private final UrlProperties urls;

	private final Clock clock;

	LastFmAuthController(LastFmClient lastFm, CredentialService credentials, CredentialDelivery delivery,
			UrlProperties urls, Clock clock) {
		this.lastFm = lastFm;
		this.credentials = credentials;
		this.delivery = delivery;
		this.urls = urls;
		this.clock = clock;
	}

	record StartRequest(@NotNull ClientType client) {
	}

	record StartResponse(String authorizeUrl) {
	}

	/** Step 1: the client gets the Last.fm page to open in a browser. */
	@Operation(operationId = "lastFmStart")
	@PostMapping("/lastfm/start")
	StartResponse start(@Valid @RequestBody StartRequest request) {
		String callback = UriComponentsBuilder.fromUriString(urls.publicBase())
			.path("/v1/auth/lastfm/callback")
			.queryParam("state", credentials.newState(PROVIDER, request.client()))
			.toUriString();
		return new StartResponse(lastFm.authorizeUrl(callback));
	}

	/** Step 2: Last.fm sends the browser here after the user approved; the bundle goes back to the client. */
	@Operation(operationId = "lastFmCallback")
	@GetMapping("/lastfm/callback")
	ResponseEntity<Void> callback(@RequestParam String state, @RequestParam(required = false) String token) {
		// A forged state is answered with a plain 401 problem: we cannot know where to send the user.
		// An expired one redirects back with "lastfm-expired" (see ApiExceptionHandler).
		OAuthState oauthState = credentials.openState(state, PROVIDER);
		if (token == null || token.isBlank()) {
			return delivery.fail(oauthState.client(), "lastfm-denied");
		}
		try {
			LastFmSession session = lastFm.getSession(token);
			var bundle = CredentialBundle.ofLastFm(session.user(), session.key(), clock.instant());
			return delivery.deliver(oauthState.client(), credentials.seal(bundle));
		}
		catch (LastFmException ex) {
			log.warn("Last.fm sign-in failed with error {}", ex.code());
			return delivery.fail(oauthState.client(), "lastfm-failed");
		}
	}

	/** Website only: forget the credentials stored in the browser. */
	@Operation(operationId = "logout")
	@PostMapping("/logout")
	ResponseEntity<Void> logout() {
		return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, delivery.clearCookie().toString()).build();
	}

}
