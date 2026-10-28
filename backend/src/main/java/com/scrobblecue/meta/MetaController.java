package com.scrobblecue.meta;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/meta")
class MetaController {

	private final MetaProperties properties;

	MetaController(MetaProperties properties) {
		this.properties = properties;
	}

	@GetMapping
	MetaResponse meta() {
		var min = properties.minClientVersion();
		return new MetaResponse(properties.apiVersion(),
				new MetaResponse.MinClientVersion(min.android(), min.web()));
	}

}
