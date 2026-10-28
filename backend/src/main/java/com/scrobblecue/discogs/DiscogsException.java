package com.scrobblecue.discogs;

/**
 * Discogs answered with an error status.
 */
public class DiscogsException extends RuntimeException {

	private final int status;

	private final Long retryAfterSeconds;

	public DiscogsException(int status, String message, Long retryAfterSeconds) {
		super(message);
		this.status = status;
		this.retryAfterSeconds = retryAfterSeconds;
	}

	public int status() {
		return status;
	}

	/** Seconds to wait before retrying, for 429 responses; otherwise {@code null}. */
	public Long retryAfterSeconds() {
		return retryAfterSeconds;
	}

}
