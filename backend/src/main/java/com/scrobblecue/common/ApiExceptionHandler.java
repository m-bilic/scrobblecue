package com.scrobblecue.common;

import java.net.URI;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.scrobblecue.credentials.CredentialDelivery;
import com.scrobblecue.credentials.CsrfHeaderMissingException;
import com.scrobblecue.credentials.InvalidSealException;
import com.scrobblecue.credentials.MissingCredentialsException;
import com.scrobblecue.credentials.StateExpiredException;
import com.scrobblecue.discogs.DiscogsException;
import com.scrobblecue.discogs.DiscogsNotConnectedException;
import com.scrobblecue.lastfm.LastFmException;

/**
 * Turns ScrobbleCue's exceptions into RFC 9457 problem details with a stable {@code type} per error,
 * so clients can react to the type instead of parsing messages. Standard Spring errors (validation,
 * malformed JSON) are handled by Spring Boot's own problem-details support.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
class ApiExceptionHandler {

	static final String TYPE_BASE = "https://scrobblecue.app/errors/";

	private final CredentialDelivery delivery;

	ApiExceptionHandler(CredentialDelivery delivery) {
		this.delivery = delivery;
	}

	/** Sign-in callbacks are browser navigations: send the user back to their client with an error. */
	@ExceptionHandler(StateExpiredException.class)
	ResponseEntity<Void> stateExpired(StateExpiredException ex) {
		return delivery.fail(ex.client(), ex.provider() + "-expired");
	}

	@ExceptionHandler(MissingCredentialsException.class)
	ProblemDetail missingCredentials(MissingCredentialsException ex) {
		return problem(HttpStatus.UNAUTHORIZED, "credentials-missing", ex.getMessage());
	}

	@ExceptionHandler(InvalidSealException.class)
	ProblemDetail invalidSeal(InvalidSealException ex) {
		return problem(HttpStatus.UNAUTHORIZED, "credentials-invalid",
				"Credentials or sign-in state are invalid or expired. Connect again.");
	}

	@ExceptionHandler(LastFmException.class)
	ProblemDetail lastFm(LastFmException ex) {
		ProblemDetail problem = switch (ex.code()) {
			case LastFmException.INVALID_SESSION_KEY -> problem(HttpStatus.UNAUTHORIZED, "lastfm-session-revoked",
					"Last.fm no longer accepts this session. Connect Last.fm again.");
			case LastFmException.SERVICE_OFFLINE, LastFmException.TEMPORARILY_UNAVAILABLE ->
				problem(HttpStatus.SERVICE_UNAVAILABLE, "lastfm-unavailable", "Last.fm is temporarily unavailable.");
			case LastFmException.RATE_LIMIT_EXCEEDED ->
				problem(HttpStatus.TOO_MANY_REQUESTS, "lastfm-rate-limited", "Last.fm rate limit reached. Try later.");
			default -> problem(HttpStatus.BAD_GATEWAY, "lastfm-error", "Last.fm returned an error.");
		};
		problem.setProperty("lastfmCode", ex.code());
		return problem;
	}

	@ExceptionHandler(CsrfHeaderMissingException.class)
	ProblemDetail csrf(CsrfHeaderMissingException ex) {
		return problem(HttpStatus.FORBIDDEN, "csrf-header-missing", ex.getMessage());
	}

	@ExceptionHandler(DiscogsNotConnectedException.class)
	ProblemDetail discogsNotConnected(DiscogsNotConnectedException ex) {
		return problem(HttpStatus.CONFLICT, "discogs-not-connected", ex.getMessage());
	}

	@ExceptionHandler(DiscogsException.class)
	ResponseEntity<ProblemDetail> discogs(DiscogsException ex) {
		ProblemDetail problem = switch (ex.status()) {
			case 401 -> problem(HttpStatus.UNAUTHORIZED, "discogs-session-revoked",
					"Discogs no longer accepts this connection. Connect Discogs again.");
			case 429 -> problem(HttpStatus.TOO_MANY_REQUESTS, "discogs-rate-limited",
					"Too many Discogs requests. Try again shortly.");
			case 404 -> problem(HttpStatus.NOT_FOUND, "discogs-not-found", "Discogs could not find this resource.");
			default -> problem(HttpStatus.BAD_GATEWAY, "discogs-error", "Discogs returned an error.");
		};
		problem.setProperty("discogsStatus", ex.status());
		var response = ResponseEntity.status(problem.getStatus());
		if (ex.retryAfterSeconds() != null) {
			response.header(HttpHeaders.RETRY_AFTER, Long.toString(ex.retryAfterSeconds()));
		}
		return response.body(problem);
	}

	private static ProblemDetail problem(HttpStatus status, String type, String detail) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
		problem.setType(URI.create(TYPE_BASE + type));
		return problem;
	}

}
