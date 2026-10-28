package com.scrobblecue.discogs;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.web.util.UriUtils;

/**
 * Builds OAuth 1.0a {@code Authorization} headers using the PLAINTEXT signature method, as recommended
 * by Discogs for requests over HTTPS: the signature is simply {@code consumerSecret&tokenSecret}.
 *
 * @see <a href="https://www.discogs.com/developers/#page:authentication,header:authentication-oauth-flow">Discogs OAuth flow</a>
 */
final class DiscogsOAuth {

	private final DiscogsProperties properties;

	private final Clock clock;

	private final SecureRandom random = new SecureRandom();

	DiscogsOAuth(DiscogsProperties properties, Clock clock) {
		this.properties = properties;
		this.clock = clock;
	}

	/** Step 1 of the flow: ask for a request token; Discogs will send the user to {@code callbackUrl}. */
	String requestTokenHeader(String callbackUrl) {
		Map<String, String> params = base("");
		params.put("oauth_callback", callbackUrl);
		return header(params);
	}

	/** Step 3 of the flow: trade the approved request token and verifier for an access token. */
	String accessTokenHeader(String requestToken, String requestTokenSecret, String verifier) {
		Map<String, String> params = base(requestTokenSecret);
		params.put("oauth_token", requestToken);
		params.put("oauth_verifier", verifier);
		return header(params);
	}

	/** Any request on behalf of a connected user. */
	String userHeader(String accessToken, String accessTokenSecret) {
		Map<String, String> params = base(accessTokenSecret);
		params.put("oauth_token", accessToken);
		return header(params);
	}

	private Map<String, String> base(String tokenSecret) {
		byte[] nonce = new byte[16];
		random.nextBytes(nonce);
		Map<String, String> params = new LinkedHashMap<>();
		params.put("oauth_consumer_key", properties.consumerKey());
		params.put("oauth_nonce", HexFormat.of().formatHex(nonce));
		params.put("oauth_signature", encode(properties.consumerSecret()) + "&" + encode(tokenSecret));
		params.put("oauth_signature_method", "PLAINTEXT");
		params.put("oauth_timestamp", Long.toString(clock.instant().getEpochSecond()));
		return params;
	}

	private static String header(Map<String, String> params) {
		return "OAuth " + params.entrySet()
			.stream()
			.map(e -> e.getKey() + "=\"" + encode(e.getValue()) + "\"")
			.collect(Collectors.joining(", "));
	}

	private static String encode(String value) {
		return value == null ? "" : UriUtils.encode(value, StandardCharsets.UTF_8);
	}

}
