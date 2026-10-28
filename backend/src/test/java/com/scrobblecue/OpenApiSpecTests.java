package com.scrobblecue;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes the OpenAPI description to {@code api/openapi.json} at the repository root. The web and
 * Android clients are generated from that file. CI fails when the committed file differs from what the
 * code produces, so the contract can't drift silently.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OpenApiSpecTests {

	private static final Path SPEC = Path.of("..", "api", "openapi.json");

	@Autowired
	MockMvcTester mvc;

	@Test
	void writeOpenApiSpec() throws Exception {
		var result = mvc.get().uri("/v3/api-docs").exchange();
		assertThat(result).hasStatusOk();

		var mapper = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();
		JsonNode spec = mapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
		// The server URL depends on the test environment, not the API
		((tools.jackson.databind.node.ObjectNode) spec).remove("servers");

		Files.createDirectories(SPEC.getParent());
		Files.writeString(SPEC, mapper.writeValueAsString(spec) + "\n", StandardCharsets.UTF_8);
		assertThat(spec.path("paths").has("/v1/lastfm/scrobbles")).isTrue();
	}

}
