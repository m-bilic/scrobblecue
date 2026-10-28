package com.scrobblecue.credentials;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/**
 * The server's sealing keys. New values are always sealed with the first (newest) key; older keys
 * are kept only so values sealed before a key rotation can still be opened.
 */
final class KeyRing {

	private static final int KEY_BYTES = 32;

	private final String currentId;

	private final Map<String, SecretKey> keys;

	private KeyRing(String currentId, Map<String, SecretKey> keys) {
		this.currentId = currentId;
		this.keys = keys;
	}

	static KeyRing parse(String spec) {
		if (spec == null || spec.isBlank()) {
			throw new IllegalStateException(
					"CREDENTIAL_KEYS is not set. Generate a key with: openssl rand -base64 32 "
							+ "and set CREDENTIAL_KEYS=k1:<key> in .env");
		}
		Map<String, SecretKey> keys = new LinkedHashMap<>();
		for (String entry : spec.split(",")) {
			String[] parts = entry.trim().split(":", 2);
			if (parts.length != 2 || parts[0].isBlank() || parts[0].contains(".")) {
				throw new IllegalStateException("CREDENTIAL_KEYS entries must look like id:base64key");
			}
			byte[] raw = Base64.getDecoder().decode(parts[1].trim());
			if (raw.length != KEY_BYTES) {
				throw new IllegalStateException("Credential key '" + parts[0] + "' must be 32 bytes, was " + raw.length);
			}
			if (keys.putIfAbsent(parts[0], new SecretKeySpec(raw, "AES")) != null) {
				throw new IllegalStateException("Duplicate credential key id '" + parts[0] + "'");
			}
		}
		return new KeyRing(keys.keySet().iterator().next(), Map.copyOf(keys));
	}

	String currentId() {
		return currentId;
	}

	SecretKey current() {
		return keys.get(currentId);
	}

	Optional<SecretKey> find(String id) {
		return Optional.ofNullable(keys.get(id));
	}

}
