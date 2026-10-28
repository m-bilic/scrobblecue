package com.scrobblecue.discogs;

/**
 * The credential bundle has no Discogs tokens yet.
 */
public class DiscogsNotConnectedException extends RuntimeException {

	public DiscogsNotConnectedException() {
		super("Discogs is not connected. Connect Discogs first.");
	}

}
