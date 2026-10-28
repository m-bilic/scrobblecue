package com.scrobblecue.discogs;

import org.springframework.stereotype.Component;

import com.scrobblecue.credentials.CredentialBundle;
import com.scrobblecue.discogs.DiscogsClient.ForwardedResponse;

/**
 * Option A: the server signs the request, sends it to Discogs and passes the response through without
 * storing it.
 */
@Component
class ForwardingDiscogsGateway implements DiscogsGateway {

	private final DiscogsClient client;

	ForwardingDiscogsGateway(DiscogsClient client) {
		this.client = client;
	}

	@Override
	public ForwardedResponse collectionPage(CredentialBundle.Discogs discogs, int page) {
		return client.collectionPage(discogs, page);
	}

}
