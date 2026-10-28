package com.scrobblecue.common;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * All code gets the current time from this {@link Clock}, never {@code Instant.now()}, so tests can
 * fix the time.
 */
@Configuration(proxyBeanMethods = false)
class ClockConfig {

	@Bean
	Clock clock() {
		return Clock.systemUTC();
	}

}
