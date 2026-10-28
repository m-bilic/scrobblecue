package com.scrobblecue.lastfm;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Computes Last.fm's {@code api_sig}: the MD5 of all parameters sorted by name, concatenated as
 * {@code name + value}, followed by the shared secret. {@code format} and {@code callback} are not
 * signed.
 *
 * @see <a href="https://www.last.fm/api/authspec#_8-signing-calls">Last.fm: signing calls</a>
 */
final class LastFmSigner {

	private static final Set<String> UNSIGNED = Set.of("format", "callback");

	private LastFmSigner() {
	}

	static String sign(Map<String, String> params, String sharedSecret) {
		var text = new StringBuilder();
		new TreeMap<>(params).forEach((name, value) -> {
			if (!UNSIGNED.contains(name)) {
				text.append(name).append(value);
			}
		});
		text.append(sharedSecret);
		try {
			byte[] digest = MessageDigest.getInstance("MD5").digest(text.toString().getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("MD5 not available", ex);
		}
	}

}
