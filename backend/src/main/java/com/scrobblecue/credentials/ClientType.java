package com.scrobblecue.credentials;

/**
 * Which kind of client started a connect flow; decides how the sealed bundle is delivered back.
 */
public enum ClientType {

	/** Website: bundle set as an HttpOnly cookie. */
	WEB,

	/** Android app: bundle passed in the fragment of a verified App Link. */
	ANDROID

}
