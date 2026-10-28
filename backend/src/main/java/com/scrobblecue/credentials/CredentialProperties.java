package com.scrobblecue.credentials;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Sealing keys, bound from {@code scrobblecue.credentials.keys} (env {@code CREDENTIAL_KEYS}).
 *
 * @param keys comma-separated {@code id:base64key} pairs, newest first. Each key is 32 bytes (AES-256).
 */
@ConfigurationProperties("scrobblecue.credentials")
public record CredentialProperties(String keys) {
}
