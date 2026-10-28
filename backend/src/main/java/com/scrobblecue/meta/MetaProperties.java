package com.scrobblecue.meta;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * API version information, bound from {@code scrobblecue.meta.*} in application.yml.
 */
@ConfigurationProperties("scrobblecue.meta")
public record MetaProperties(String apiVersion, MinClientVersion minClientVersion) {

	public record MinClientVersion(String android, String web) {
	}

}
