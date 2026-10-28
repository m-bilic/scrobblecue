package com.scrobblecue.credentials;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

import org.springframework.stereotype.Component;

/**
 * Encrypts and authenticates small values with AES-256-GCM.
 *
 * <p>Output format: {@code keyId.base64url(nonce ‖ ciphertext ‖ tag)}. The {@code purpose} is bound
 * as additional authenticated data, so a value sealed for one purpose (e.g. an OAuth state) can
 * never be opened as another (e.g. a credential bundle).
 */
@Component
class Sealer {

	private static final String CIPHER = "AES/GCM/NoPadding";

	private static final int NONCE_BYTES = 12;

	private static final int TAG_BITS = 128;

	private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

	private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

	private final KeyRing keyRing;

	private final SecureRandom random = new SecureRandom();

	Sealer(CredentialProperties properties) {
		this.keyRing = KeyRing.parse(properties.keys());
	}

	String seal(byte[] plaintext, String purpose) {
		byte[] nonce = new byte[NONCE_BYTES];
		random.nextBytes(nonce);
		try {
			Cipher cipher = Cipher.getInstance(CIPHER);
			cipher.init(Cipher.ENCRYPT_MODE, keyRing.current(), new GCMParameterSpec(TAG_BITS, nonce));
			cipher.updateAAD(purpose.getBytes(StandardCharsets.UTF_8));
			byte[] sealed = cipher.doFinal(plaintext);
			byte[] out = new byte[NONCE_BYTES + sealed.length];
			System.arraycopy(nonce, 0, out, 0, NONCE_BYTES);
			System.arraycopy(sealed, 0, out, NONCE_BYTES, sealed.length);
			return keyRing.currentId() + "." + ENCODER.encodeToString(out);
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException("Sealing failed", ex);
		}
	}

	byte[] open(String value, String purpose) {
		int dot = value == null ? -1 : value.indexOf('.');
		if (dot <= 0) {
			throw new InvalidSealException("Malformed sealed value");
		}
		SecretKey key = keyRing.find(value.substring(0, dot))
			.orElseThrow(() -> new InvalidSealException("Unknown key id"));
		byte[] in;
		try {
			in = DECODER.decode(value.substring(dot + 1));
		}
		catch (IllegalArgumentException ex) {
			throw new InvalidSealException("Malformed sealed value");
		}
		if (in.length <= NONCE_BYTES) {
			throw new InvalidSealException("Malformed sealed value");
		}
		try {
			Cipher cipher = Cipher.getInstance(CIPHER);
			cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, in, 0, NONCE_BYTES));
			cipher.updateAAD(purpose.getBytes(StandardCharsets.UTF_8));
			return cipher.doFinal(in, NONCE_BYTES, in.length - NONCE_BYTES);
		}
		catch (GeneralSecurityException ex) {
			throw new InvalidSealException("Sealed value was tampered with or sealed for another purpose");
		}
	}

}
