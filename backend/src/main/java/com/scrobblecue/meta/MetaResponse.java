package com.scrobblecue.meta;

/**
 * Response of {@code GET /v1/meta}. Clients compare their own version with
 * {@code minClientVersion} and ask the user to update when they are older.
 */
public record MetaResponse(String apiVersion, MinClientVersion minClientVersion) {

	public record MinClientVersion(String android, String web) {
	}

}
