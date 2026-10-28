package com.scrobblecue.discogs;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import com.scrobblecue.common.TokenBucket;
import com.scrobblecue.credentials.CredentialBundle;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Calls the Discogs API from this server: the OAuth handshake and forwarded collection pages. Nothing
 * else in the backend knows Discogs' URLs. Every call goes through a shared rate limiter, because all
 * requests from this server count against one IP-based Discogs limit.
 */
@Component
public class DiscogsClient {

	/** Collection page size; the maximum Discogs allows. */
	public static final int PAGE_SIZE = 100;

	private final RestClient http;

	private final DiscogsProperties properties;

	private final DiscogsOAuth oauth;

	private final TokenBucket rateLimit;

	private final JsonMapper jsonMapper;

	DiscogsClient(RestClient.Builder builder, DiscogsProperties properties, JsonMapper jsonMapper, Clock clock) {
		this.http = builder.baseUrl(properties.apiUrl())
			.defaultHeader(HttpHeaders.USER_AGENT, properties.userAgent())
			.build();
		this.properties = properties;
		this.oauth = new DiscogsOAuth(properties, clock);
		this.rateLimit = new TokenBucket(properties.requestsPerMinute(), clock);
		this.jsonMapper = jsonMapper;
	}

	/** Request token and secret from step 1 of the OAuth flow. */
	public record TokenPair(String token, String secret) {

		@Override
		public String toString() {
			return "TokenPair[...]";
		}

	}

	/** Raw response forwarded to the client as-is, without being stored. */
	public record ForwardedResponse(String body) {
	}

	public TokenPair requestToken(String callbackUrl) {
		String body = send(http.get()
			.uri("/oauth/request_token")
			.header(HttpHeaders.AUTHORIZATION, oauth.requestTokenHeader(callbackUrl)));
		return tokenPair(body);
	}

	/** URL of the page where the user approves ScrobbleCue. */
	public String authorizeUrl(String requestToken) {
		return UriComponentsBuilder.fromUriString(properties.authorizeUrl())
			.queryParam("oauth_token", requestToken)
			.encode()
			.toUriString();
	}

	public TokenPair accessToken(String requestToken, String requestTokenSecret, String verifier) {
		String body = send(http.post()
			.uri("/oauth/access_token")
			.header(HttpHeaders.AUTHORIZATION, oauth.accessTokenHeader(requestToken, requestTokenSecret, verifier))
			.contentType(MediaType.APPLICATION_FORM_URLENCODED));
		return tokenPair(body);
	}

	/** The Discogs username belonging to an access token. */
	public String identity(String accessToken, String accessTokenSecret) {
		String body = send(http.get()
			.uri("/oauth/identity")
			.header(HttpHeaders.AUTHORIZATION, oauth.userHeader(accessToken, accessTokenSecret)));
		try {
			Object username = jsonMapper.readValue(body, Map.class).get("username");
			if (username == null) {
				throw new DiscogsException(502, "Discogs identity without username", null);
			}
			return username.toString();
		}
		catch (JacksonException ex) {
			throw new DiscogsException(502, "Unreadable identity response", null);
		}
	}

	/** One page of the user's collection ("All" folder), as Discogs returns it. */
	public ForwardedResponse collectionPage(CredentialBundle.Discogs discogs, int page) {
		String body = send(http.get()
			.uri("/users/{user}/collection/folders/0/releases?page={page}&per_page={size}", discogs.user(), page,
					PAGE_SIZE)
			.header(HttpHeaders.AUTHORIZATION, oauth.userHeader(discogs.token(), discogs.tokenSecret())));
		return new ForwardedResponse(body);
	}

	private String send(RestClient.RequestHeadersSpec<?> request) {
		Duration wait = rateLimit.tryTake();
		if (!wait.isZero()) {
			throw new DiscogsException(429, "ScrobbleCue's Discogs request limit reached", Math.max(1, wait.toSeconds()));
		}
		ResponseEntity<String> response = request.retrieve()
			// Status codes are translated below, so the client gets a meaningful problem type
			.onStatus(status -> true, (req, res) -> {
			})
			.toEntity(String.class);
		HttpStatusCode status = response.getStatusCode();
		if (status.is2xxSuccessful()) {
			return response.getBody() == null ? "" : response.getBody();
		}
		Long retryAfter = null;
		String retryHeader = response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER);
		if (retryHeader != null && retryHeader.matches("\\d+")) {
			retryAfter = Long.parseLong(retryHeader);
		}
		throw new DiscogsException(status.value(), "Discogs returned " + status.value(), retryAfter);
	}

	private static TokenPair tokenPair(String formBody) {
		var params = UriComponentsBuilder.fromUriString("?" + formBody).build().getQueryParams();
		String token = params.getFirst("oauth_token");
		String secret = params.getFirst("oauth_token_secret");
		if (token == null || secret == null) {
			throw new DiscogsException(502, "Discogs returned no token", null);
		}
		return new TokenPair(token, secret);
	}

}
