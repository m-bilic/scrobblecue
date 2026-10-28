package com.scrobblecue.meta;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

@SpringBootTest(properties = {
		"scrobblecue.meta.min-client-version.android=1.2.0",
		"scrobblecue.meta.min-client-version.web=1.0.0" })
@AutoConfigureMockMvc
class MetaControllerTests {

	@Autowired
	MockMvcTester mvc;

	@Test
	void returnsApiVersionAndMinimumClientVersions() {
		var result = mvc.get().uri("/v1/meta").exchange();

		assertThat(result).hasStatus(HttpStatus.OK);
		assertThat(result).bodyJson().extractingPath("$.apiVersion").isEqualTo("v1");
		assertThat(result).bodyJson().extractingPath("$.minClientVersion.android").isEqualTo("1.2.0");
		assertThat(result).bodyJson().extractingPath("$.minClientVersion.web").isEqualTo("1.0.0");
	}

}
