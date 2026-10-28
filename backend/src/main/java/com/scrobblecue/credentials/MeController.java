package com.scrobblecue.credentials;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Who is connected. The website needs this because its credential cookie is HttpOnly and cannot be
 * read by page scripts.
 */
@RestController
class MeController {

	/**
	 * @param lastfmUser connected Last.fm username
	 * @param discogsUser connected Discogs username, or {@code null}
	 */
	record MeResponse(String lastfmUser, String discogsUser) {
	}

	@GetMapping("/v1/me")
	MeResponse me(CredentialBundle bundle) {
		return new MeResponse(bundle.lastfm().user(), bundle.discogs() == null ? null : bundle.discogs().user());
	}

}
