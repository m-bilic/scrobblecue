package com.scrobblecue.credentials;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

import org.springframework.stereotype.Service;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Seals and opens credential bundles and OAuth states.
 */
@Service
public class CredentialService {

	private static final String BUNDLE_PURPOSE = "credential-bundle";

	private static final String STATE_PURPOSE = "oauth-state";

	private static final String REQUEST_SECRET_PURPOSE = "oauth-request-secret";

	private static final Duration STATE_LIFETIME = Duration.ofMinutes(10);

	private final Sealer sealer;

	private final JsonMapper jsonMapper;

	private final Clock clock;

	private final SecureRandom random = new SecureRandom();

	CredentialService(Sealer sealer, JsonMapper jsonMapper, Clock clock) {
		this.sealer = sealer;
		this.jsonMapper = jsonMapper;
		this.clock = clock;
	}

	public String seal(CredentialBundle bundle) {
		return sealer.seal(jsonMapper.writeValueAsBytes(bundle), BUNDLE_PURPOSE);
	}

	public CredentialBundle open(String sealed) {
		CredentialBundle bundle = read(sealer.open(sealed, BUNDLE_PURPOSE), CredentialBundle.class);
		if (bundle.v() != CredentialBundle.VERSION || bundle.lastfm() == null) {
			throw new InvalidSealException("Unsupported credential bundle");
		}
		return bundle;
	}

	public String newState(String provider, ClientType client) {
		return newState(provider, client, null);
	}

	/**
	 * State that also carries the user's current sealed bundle (Discogs), so the bundle can be extended
	 * in the callback without the server remembering anything.
	 */
	public String newState(String provider, ClientType client, String sealedBundle) {
		byte[] nonce = new byte[16];
		random.nextBytes(nonce);
		var state = new OAuthState(provider, client, HexFormat.of().formatHex(nonce),
				clock.instant().plus(STATE_LIFETIME), sealedBundle);
		return sealer.seal(jsonMapper.writeValueAsBytes(state), STATE_PURPOSE);
	}

	/** Seals a short-lived OAuth request-token secret, to be kept in the user's browser during sign-in. */
	public String sealRequestSecret(String secret) {
		var value = new TemporarySecret(secret, clock.instant().plus(STATE_LIFETIME));
		return sealer.seal(jsonMapper.writeValueAsBytes(value), REQUEST_SECRET_PURPOSE);
	}

	public String openRequestSecret(String sealed) {
		TemporarySecret value = read(sealer.open(sealed, REQUEST_SECRET_PURPOSE), TemporarySecret.class);
		if (Instant.now(clock).isAfter(value.expiresAt())) {
			throw new InvalidSealException("Request secret expired");
		}
		return value.secret();
	}

	record TemporarySecret(String secret, Instant expiresAt) {
	}

	public OAuthState openState(String sealed, String expectedProvider) {
		OAuthState state = read(sealer.open(sealed, STATE_PURPOSE), OAuthState.class);
		if (!expectedProvider.equals(state.provider())) {
			throw new InvalidSealException("State belongs to another provider");
		}
		if (Instant.now(clock).isAfter(state.expiresAt())) {
			throw new StateExpiredException(state.provider(), state.client());
		}
		return state;
	}

	private <T> T read(byte[] json, Class<T> type) {
		try {
			return jsonMapper.readValue(json, type);
		}
		catch (JacksonException ex) {
			throw new InvalidSealException("Unreadable sealed content");
		}
	}

}
