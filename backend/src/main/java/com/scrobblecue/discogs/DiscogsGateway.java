package com.scrobblecue.discogs;

import com.scrobblecue.credentials.CredentialBundle;
import com.scrobblecue.discogs.DiscogsClient.ForwardedResponse;

/**
 * How authenticated Discogs data reaches the client. The only implementation today is
 * {@link ForwardingDiscogsGateway} (Option A: the server forwards). Option B (the server only signs,
 * the client sends) would be a second implementation, chosen by configuration – see SPEC.md, D8.
 */
public interface DiscogsGateway {

	ForwardedResponse collectionPage(CredentialBundle.Discogs discogs, int page);

}
