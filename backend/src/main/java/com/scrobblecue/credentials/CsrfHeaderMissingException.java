package com.scrobblecue.credentials;

/**
 * A state-changing request authenticated by the website's cookie arrived without ScrobbleCue's custom
 * header. Browsers only let our own pages add that header to cross-origin requests, so its absence
 * points to a forged request from another site.
 */
public class CsrfHeaderMissingException extends RuntimeException {

	public CsrfHeaderMissingException() {
		super("Missing " + CredentialsArgumentResolver.CSRF_HEADER + " header");
	}

}
