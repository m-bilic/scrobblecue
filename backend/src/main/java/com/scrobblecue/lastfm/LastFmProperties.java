package com.scrobblecue.lastfm;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Last.fm API settings, bound from {@code scrobblecue.lastfm.*}.
 *
 * @param apiKey public API key (env {@code LASTFM_API_KEY})
 * @param sharedSecret signing secret (env {@code LASTFM_SHARED_SECRET}) – never leaves the server
 * @param apiUrl web service endpoint
 * @param authUrl page where users approve ScrobbleCue
 */
@ConfigurationProperties("scrobblecue.lastfm")
public record LastFmProperties(String apiKey, String sharedSecret, String apiUrl, String authUrl) {

	@Override
	public String toString() {
		return "LastFmProperties[apiUrl=" + apiUrl + "]";
	}

}
