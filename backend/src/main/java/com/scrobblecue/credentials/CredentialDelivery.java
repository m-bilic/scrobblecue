package com.scrobblecue.credentials;

import java.net.URI;
import java.time.Duration;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import com.scrobblecue.common.UrlProperties;

/**
 * Hands a freshly sealed bundle to the client at the end of a connect flow, without the server
 * keeping a copy.
 */
@Component
public class CredentialDelivery {

	/** Name of the website's credential cookie. */
	public static final String COOKIE_NAME = "sc_credentials";

	// Browsers cap cookie lifetime at 400 days
	private static final Duration COOKIE_MAX_AGE = Duration.ofDays(400);

	private final UrlProperties urls;

	CredentialDelivery(UrlProperties urls) {
		this.urls = urls;
	}

	/** Redirect back to the client with the sealed bundle. */
	public ResponseEntity<Void> deliver(ClientType client, String sealedBundle) {
		return switch (client) {
			case WEB -> ResponseEntity.status(HttpStatus.FOUND)
				.header(HttpHeaders.SET_COOKIE, cookie(sealedBundle, COOKIE_MAX_AGE).toString())
				.location(URI.create(urls.web()))
				.build();
			// The fragment is never sent to any server; App Link verification ensures only our app opens it
			case ANDROID -> redirect(UriComponentsBuilder.fromUriString(urls.androidAuthLink())
				.fragment("bundle=" + sealedBundle)
				.build()
				.toUri());
		};
	}

	/** Redirect back to the client with an error code instead of a bundle. */
	public ResponseEntity<Void> fail(ClientType client, String errorCode) {
		return switch (client) {
			case WEB -> redirect(UriComponentsBuilder.fromUriString(urls.web())
				.queryParam("authError", errorCode)
				.build()
				.toUri());
			case ANDROID -> redirect(UriComponentsBuilder.fromUriString(urls.androidAuthLink())
				.fragment("error=" + errorCode)
				.build()
				.toUri());
		};
	}

	/** Cookie that holds the website's credentials. */
	public ResponseCookie bundleCookie(String sealedBundle) {
		return cookie(sealedBundle, COOKIE_MAX_AGE);
	}

	/** Cookie that removes the website's credentials. */
	public ResponseCookie clearCookie() {
		return cookie("", Duration.ZERO);
	}

	private ResponseCookie cookie(String value, Duration maxAge) {
		return ResponseCookie.from(COOKIE_NAME, value)
			.httpOnly(true)
			.secure(true)
			.sameSite("Strict")
			.path("/v1")
			.maxAge(maxAge)
			.build();
	}

	private static ResponseEntity<Void> redirect(URI location) {
		return ResponseEntity.status(HttpStatus.FOUND).location(location).build();
	}

}
