package com.scrobblecue.credentials;

import java.time.Instant;

/**
 * Data carried through a provider's sign-in page in the sealed {@code state} value, so the server
 * keeps nothing between "start" and "callback".
 *
 * @param provider which flow this state belongs to, e.g. {@code lastfm}
 * @param client who started the flow
 * @param nonce random value, makes every state unique
 * @param expiresAt the state is rejected after this moment
 * @param bundle the user's sealed bundle at the start of the flow (Discogs), otherwise {@code null}
 */
public record OAuthState(String provider, ClientType client, String nonce, Instant expiresAt, String bundle) {

	@Override
	public String toString() {
		return "OAuthState[provider=" + provider + ", client=" + client + "]";
	}

}
