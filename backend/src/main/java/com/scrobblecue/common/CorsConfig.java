package com.scrobblecue.common;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Lets the website (e.g. scrobblecue.app) call the API (e.g. api.scrobblecue.app) with its credential
 * cookie. Only the configured website origin is allowed.
 */
@Configuration(proxyBeanMethods = false)
class CorsConfig implements WebMvcConfigurer {

	private final UrlProperties urls;

	CorsConfig(UrlProperties urls) {
		this.urls = urls;
	}

	@Override
	public void addCorsMappings(CorsRegistry registry) {
		registry.addMapping("/v1/**")
			.allowedOrigins(urls.web())
			.allowedMethods("GET", "POST", "PUT", "DELETE")
			.allowedHeaders(HttpHeaders.CONTENT_TYPE, HttpHeaders.AUTHORIZATION, "X-ScrobbleCue", "X-Client-Version")
			.exposedHeaders(HttpHeaders.RETRY_AFTER)
			.allowCredentials(true);
	}

}
