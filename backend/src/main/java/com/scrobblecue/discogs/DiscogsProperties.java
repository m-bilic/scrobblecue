package com.scrobblecue.discogs;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Discogs API settings, bound from {@code scrobblecue.discogs.*}.
 *
 * @param consumerKey application key (env {@code DISCOGS_CONSUMER_KEY})
 * @param consumerSecret application secret (env {@code DISCOGS_CONSUMER_SECRET}) – never leaves the server
 * @param userAgent identifies ScrobbleCue to Discogs, as their API requires (env {@code DISCOGS_USER_AGENT})
 * @param apiUrl API base URL
 * @param authorizeUrl page where users approve ScrobbleCue
 * @param requestsPerMinute limit for requests forwarded from this server, kept below Discogs' 60/min
 */
@ConfigurationProperties("scrobblecue.discogs")
public record DiscogsProperties(String consumerKey, String consumerSecret, String userAgent, String apiUrl,
		String authorizeUrl, int requestsPerMinute) {

	@Override
	public String toString() {
		return "DiscogsProperties[apiUrl=" + apiUrl + "]";
	}

}
