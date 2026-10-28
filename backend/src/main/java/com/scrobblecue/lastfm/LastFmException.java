package com.scrobblecue.lastfm;

/**
 * Last.fm answered with an error.
 *
 * @see <a href="https://www.last.fm/api/errorcodes">Last.fm error codes</a>
 */
public class LastFmException extends RuntimeException {

	/** Invalid session key – the user revoked access or the key is wrong. */
	public static final int INVALID_SESSION_KEY = 9;

	/** Authentication failed, e.g. an expired or already used token. */
	public static final int AUTHENTICATION_FAILED = 4;

	/** The token has not been authorised by the user. */
	public static final int UNAUTHORIZED_TOKEN = 14;

	/** Service offline. */
	public static final int SERVICE_OFFLINE = 11;

	/** Service temporarily unavailable. */
	public static final int TEMPORARILY_UNAVAILABLE = 16;

	/** Rate limit exceeded. */
	public static final int RATE_LIMIT_EXCEEDED = 29;

	private final int code;

	public LastFmException(int code, String message) {
		super(message);
		this.code = code;
	}

	public int code() {
		return code;
	}

}
