package com.scrobblecue.lastfm;

/**
 * A user's Last.fm session. The key does not expire; the user can revoke it in their Last.fm settings.
 */
public record LastFmSession(String user, String key) {

	@Override
	public String toString() {
		return "LastFmSession[user=" + user + "]";
	}

}
