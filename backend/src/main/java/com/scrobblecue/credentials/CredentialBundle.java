package com.scrobblecue.credentials;

import java.time.Instant;

/**
 * Everything ScrobbleCue needs to act on a user's behalf. It only ever exists in sealed form on
 * the user's device, and in memory on the server for the duration of one request.
 *
 * @param v format version
 * @param lastfm Last.fm session
 * @param discogs Discogs tokens, {@code null} until Discogs is connected
 * @param issuedAt when this bundle was sealed
 */
public record CredentialBundle(int v, LastFm lastfm, Discogs discogs, Instant issuedAt) {

	public static final int VERSION = 1;

	public record LastFm(String user, String sessionKey) {

		@Override
		public String toString() {
			return "LastFm[user=" + user + "]";
		}

	}

	public record Discogs(String user, String token, String tokenSecret) {

		@Override
		public String toString() {
			return "Discogs[user=" + user + "]";
		}

	}

	public static CredentialBundle ofLastFm(String user, String sessionKey, Instant now) {
		return new CredentialBundle(VERSION, new LastFm(user, sessionKey), null, now);
	}

	/** A copy with Discogs connected (or replaced). */
	public CredentialBundle withDiscogs(Discogs discogs, Instant now) {
		return new CredentialBundle(VERSION, lastfm, discogs, now);
	}

	/** A copy without Discogs. */
	public CredentialBundle withoutDiscogs(Instant now) {
		return new CredentialBundle(VERSION, lastfm, null, now);
	}

	@Override
	public String toString() {
		// Never print tokens, e.g. in logs or exception messages
		return "CredentialBundle[lastfm=" + (lastfm != null) + ", discogs=" + (discogs != null) + "]";
	}

}
