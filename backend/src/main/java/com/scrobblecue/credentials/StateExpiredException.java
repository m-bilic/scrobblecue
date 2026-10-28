package com.scrobblecue.credentials;

/**
 * A genuine sign-in state that has expired, for example because the user approved access long after
 * starting. Unlike a forged state it can be trusted to say where the user came from, so the user is
 * sent back to their client with an error instead of seeing a bare error response.
 */
public class StateExpiredException extends InvalidSealException {

	private final String provider;

	private final ClientType client;

	public StateExpiredException(String provider, ClientType client) {
		super("State expired");
		this.provider = provider;
		this.client = client;
	}

	public String provider() {
		return provider;
	}

	public ClientType client() {
		return client;
	}

}
