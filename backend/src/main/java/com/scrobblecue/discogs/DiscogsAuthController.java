package com.scrobblecue.discogs;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import com.scrobblecue.common.UrlProperties;
import com.scrobblecue.credentials.ClientType;
import com.scrobblecue.credentials.CredentialBundle;
import com.scrobblecue.credentials.CredentialDelivery;
import com.scrobblecue.credentials.CredentialService;
import com.scrobblecue.credentials.InvalidSealException;
import com.scrobblecue.credentials.OAuthState;

/**
 * Connecting Discogs (OAuth 1.0a), which adds Discogs tokens to the user's existing credential bundle.
 *
 * <p>OAuth 1.0a needs a request-token secret in the last step, but Discogs only hands it out after it
 * knows the callback URL. So the flow has one extra browser step: {@code /redirect} fetches the request
 * token and keeps its secret in a sealed, 10-minute cookie in the user's own browser. The server stores
 * nothing at any point.
 *
 * <ol>
 * <li>{@code POST /start} (API call with the bundle) → URL of {@code /redirect} with a sealed state
 * <li>{@code GET /redirect} (in the browser) → request token, secret cookie, redirect to Discogs
 * <li>{@code GET /callback} (in the browser, after approval) → access token, extended bundle to the client
 * </ol>
 */
@RestController
@RequestMapping("/v1/auth/discogs")
class DiscogsAuthController {

	private static final Logger log = LoggerFactory.getLogger(DiscogsAuthController.class);

	private static final String PROVIDER = "discogs";

	static final String SECRET_COOKIE = "sc_discogs_request";

	private static final String SECRET_COOKIE_PATH = "/v1/auth/discogs";

	private final DiscogsClient discogs;

	private final CredentialService credentials;

	private final CredentialDelivery delivery;

	private final UrlProperties urls;

	private final Clock clock;

	DiscogsAuthController(DiscogsClient discogs, CredentialService credentials, CredentialDelivery delivery,
			UrlProperties urls, Clock clock) {
		this.discogs = discogs;
		this.credentials = credentials;
		this.delivery = delivery;
		this.urls = urls;
		this.clock = clock;
	}

	record StartRequest(@NotNull ClientType client) {
	}

	record StartResponse(String authorizeUrl) {
	}

	record BundleResponse(String bundle) {
	}

	/** Step 1: returns the URL the client opens in a browser. Requires Last.fm to be connected. */
	@PostMapping("/start")
	StartResponse start(@Valid @RequestBody StartRequest request, CredentialBundle bundle) {
		String state = credentials.newState(PROVIDER, request.client(), credentials.seal(bundle));
		return new StartResponse(url("/redirect", state));
	}

	/** Step 2 (browser): get a request token, remember its secret in the browser, go to Discogs. */
	@GetMapping("/redirect")
	ResponseEntity<Void> redirect(@RequestParam String state) {
		OAuthState oauthState = credentials.openState(state, PROVIDER);
		try {
			var requestToken = discogs.requestToken(url("/callback", state));
			return ResponseEntity.status(HttpStatus.FOUND)
				.header(HttpHeaders.SET_COOKIE,
						secretCookie(credentials.sealRequestSecret(requestToken.secret()), Duration.ofMinutes(10)))
				.location(URI.create(discogs.authorizeUrl(requestToken.token())))
				.build();
		}
		catch (DiscogsException ex) {
			log.warn("Discogs request token failed with status {}", ex.status());
			return delivery.fail(oauthState.client(), "discogs-failed");
		}
	}

	/** Step 3 (browser): Discogs sends the user here after approval; the extended bundle goes to the client. */
	@GetMapping("/callback")
	ResponseEntity<Void> callback(@RequestParam String state,
			@RequestParam(name = "oauth_token", required = false) String requestToken,
			@RequestParam(name = "oauth_verifier", required = false) String verifier,
			@CookieValue(name = SECRET_COOKIE, required = false) String sealedSecret) {
		OAuthState oauthState = credentials.openState(state, PROVIDER);
		ResponseEntity<Void> response;
		if (requestToken == null || verifier == null) {
			response = delivery.fail(oauthState.client(), "discogs-denied");
		}
		else if (sealedSecret == null) {
			response = delivery.fail(oauthState.client(), "discogs-expired");
		}
		else {
			response = complete(oauthState, requestToken, verifier, sealedSecret);
		}
		// The request secret is single-use: always remove it
		return ResponseEntity.status(response.getStatusCode())
			.headers(response.getHeaders())
			.header(HttpHeaders.SET_COOKIE, secretCookie("", Duration.ZERO))
			.build();
	}

	/**
	 * Removes Discogs from the bundle. Apps receive the new bundle in the body; the website gets its
	 * cookie replaced.
	 */
	@PostMapping("/disconnect")
	ResponseEntity<BundleResponse> disconnect(CredentialBundle bundle,
			@RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
		String sealed = credentials.seal(bundle.withoutDiscogs(clock.instant()));
		if (authorization != null) {
			return ResponseEntity.ok(new BundleResponse(sealed));
		}
		return ResponseEntity.noContent()
			.header(HttpHeaders.SET_COOKIE, delivery.bundleCookie(sealed).toString())
			.build();
	}

	private ResponseEntity<Void> complete(OAuthState oauthState, String requestToken, String verifier,
			String sealedSecret) {
		try {
			String requestSecret = credentials.openRequestSecret(sealedSecret);
			var access = discogs.accessToken(requestToken, requestSecret, verifier);
			String user = discogs.identity(access.token(), access.secret());
			CredentialBundle bundle = credentials.open(oauthState.bundle())
				.withDiscogs(new CredentialBundle.Discogs(user, access.token(), access.secret()), clock.instant());
			return delivery.deliver(oauthState.client(), credentials.seal(bundle));
		}
		catch (InvalidSealException ex) {
			return delivery.fail(oauthState.client(), "discogs-expired");
		}
		catch (DiscogsException ex) {
			log.warn("Discogs sign-in failed with status {}", ex.status());
			return delivery.fail(oauthState.client(), "discogs-failed");
		}
	}

	private String url(String path, String state) {
		return UriComponentsBuilder.fromUriString(urls.publicBase())
			.path("/v1/auth/discogs" + path)
			.queryParam("state", state)
			.toUriString();
	}

	private static String secretCookie(String value, Duration maxAge) {
		// Lax, not Strict: the callback is a top-level navigation coming from discogs.com
		return ResponseCookie.from(SECRET_COOKIE, value)
			.httpOnly(true)
			.secure(true)
			.sameSite("Lax")
			.path(SECRET_COOKIE_PATH)
			.maxAge(maxAge)
			.build()
			.toString();
	}

}
