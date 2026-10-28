package com.scrobblecue.common;

import java.time.Clock;
import java.time.Duration;

/**
 * A simple in-memory rate limiter: holds up to {@code capacity} tokens and refills them evenly over one
 * minute. Used to keep requests forwarded from this server under an upstream API's limit. State is
 * per server instance and lost on restart, which is fine for that purpose.
 */
public final class TokenBucket {

	private final int capacity;

	private final double tokensPerNano;

	private final Clock clock;

	private double tokens;

	private long lastRefillNanos;

	public TokenBucket(int perMinute, Clock clock) {
		if (perMinute <= 0) {
			throw new IllegalArgumentException("perMinute must be positive");
		}
		this.capacity = perMinute;
		this.tokensPerNano = perMinute / (double) Duration.ofMinutes(1).toNanos();
		this.clock = clock;
		this.tokens = perMinute;
		this.lastRefillNanos = nanos();
	}

	/**
	 * Takes one token if available.
	 * @return {@link Duration#ZERO} when a token was taken, otherwise how long until one is available
	 */
	public synchronized Duration tryTake() {
		refill();
		if (tokens >= 1) {
			tokens -= 1;
			return Duration.ZERO;
		}
		return Duration.ofNanos((long) Math.ceil((1 - tokens) / tokensPerNano));
	}

	private void refill() {
		long now = nanos();
		tokens = Math.min(capacity, tokens + (now - lastRefillNanos) * tokensPerNano);
		lastRefillNanos = now;
	}

	private long nanos() {
		return Duration.ofMillis(clock.millis()).toNanos();
	}

}
