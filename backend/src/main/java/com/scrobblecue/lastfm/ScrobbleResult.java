package com.scrobblecue.lastfm;

/**
 * What Last.fm did with one scrobble.
 *
 * @param status accepted or ignored
 * @param ignoredCode Last.fm's reason code when ignored, otherwise {@code null}
 * @param ignoredMessage Last.fm's reason text when ignored, otherwise {@code null}
 */
public record ScrobbleResult(Status status, Integer ignoredCode, String ignoredMessage) {

	public enum Status {

		ACCEPTED, IGNORED

	}

	static ScrobbleResult accepted() {
		return new ScrobbleResult(Status.ACCEPTED, null, null);
	}

	static ScrobbleResult ignored(int code, String message) {
		return new ScrobbleResult(Status.IGNORED, code, message);
	}

}
