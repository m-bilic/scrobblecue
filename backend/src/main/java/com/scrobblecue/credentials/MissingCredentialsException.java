package com.scrobblecue.credentials;

/**
 * The request carried no credential bundle.
 */
public class MissingCredentialsException extends RuntimeException {

	public MissingCredentialsException() {
		super("No credentials. Connect Last.fm first.");
	}

}
