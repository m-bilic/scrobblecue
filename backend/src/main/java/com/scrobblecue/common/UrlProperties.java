package com.scrobblecue.common;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Public addresses, bound from {@code scrobblecue.urls.*}.
 *
 * @param publicBase where this backend is reachable, used to build provider callback URLs
 * @param web where the website runs; users return there after connecting an account
 * @param androidAuthLink verified App Link the Android app receives its bundle on
 */
@ConfigurationProperties("scrobblecue.urls")
public record UrlProperties(String publicBase, String web, String androidAuthLink) {
}
