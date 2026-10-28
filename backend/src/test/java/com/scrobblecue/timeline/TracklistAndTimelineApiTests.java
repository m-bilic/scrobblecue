package com.scrobblecue.timeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/**
 * The stateless endpoints over HTTP: no credentials needed.
 */
@SpringBootTest
@AutoConfigureMockMvc
class TracklistAndTimelineApiTests {

	@Autowired
	MockMvcTester mvc;

	@Test
	void prepareAcceptsRawDiscogsJson() throws IOException {
		String release;
		try (var in = getClass().getResourceAsStream("/discogs/release-249504.json")) {
			release = new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}

		var result = mvc.post().uri("/v1/tracklists/prepare")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"release\":" + release + ",\"corrections\":[{\"position\":\"B\",\"skip\":true}]}")
			.exchange();

		assertThat(result).hasStatusOk();
		assertThat(result).bodyJson().extractingPath("$.groups[0].label").isEqualTo("A");
		assertThat(result).bodyJson().extractingPath("$.groups[1].tracks[0].scrobblable").isEqualTo(false);
	}

	@Test
	void timelineNeedsExactlyOneOfStartedAtOrEndedAt() {
		String tracks = "\"tracks\":[{\"position\":\"A1\",\"durationSec\":200}]";

		var both = mvc.post().uri("/v1/scrobbles/timeline")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{" + tracks + ",\"startedAt\":\"2026-10-27T20:00:00Z\",\"endedAt\":\"2026-10-27T20:10:00Z\"}")
			.exchange();
		var instant = mvc.post().uri("/v1/scrobbles/timeline")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{" + tracks + ",\"endedAt\":\"2026-10-27T20:10:00Z\"}")
			.exchange();

		assertThat(both).hasStatus(HttpStatus.BAD_REQUEST);
		assertThat(instant).hasStatusOk();
		assertThat(instant).bodyJson().extractingPath("$.tracks[0].startsAt").isEqualTo("2026-10-27T20:06:40Z");
	}

}
