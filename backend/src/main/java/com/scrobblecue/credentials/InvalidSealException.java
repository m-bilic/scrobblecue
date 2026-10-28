package com.scrobblecue.credentials;

/**
 * A sealed value could not be opened: malformed, unknown key, tampered with, expired, or sealed for
 * another purpose. The reason is deliberately not exposed to clients.
 */
public class InvalidSealException extends RuntimeException {

	public InvalidSealException(String message) {
		super(message);
	}

}
