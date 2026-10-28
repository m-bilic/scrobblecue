package com.scrobblecue.lastfm;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

class LastFmSignerTests {

	@Test
	void signsSortedParametersWithoutFormat() {
		Map<String, String> params = new LinkedHashMap<>();
		params.put("token", "tok456");
		params.put("method", "auth.getSession");
		params.put("format", "json");
		params.put("api_key", "abc123");

		// md5("api_keyabc123" + "methodauth.getSession" + "tokentok456" + "secret789")
		assertThat(LastFmSigner.sign(params, "secret789")).isEqualTo("be87f72a198902bffdc0c6d3783ee5b0");
	}

}
