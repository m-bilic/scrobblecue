package com.scrobblecue.discogs;

import jakarta.validation.constraints.Min;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.scrobblecue.credentials.CredentialBundle;

/**
 * Forwards the user's Discogs collection, one page of 100 records at a time. The response is Discogs'
 * own JSON, passed through unchanged and not stored; the client caches it.
 */
@RestController
@RequestMapping("/v1/discogs")
@Validated
class DiscogsCollectionController {

	private final DiscogsGateway gateway;

	DiscogsCollectionController(DiscogsGateway gateway) {
		this.gateway = gateway;
	}

	@GetMapping(path = "/collection", produces = MediaType.APPLICATION_JSON_VALUE)
	ResponseEntity<String> collection(@RequestParam(defaultValue = "1") @Min(1) int page, CredentialBundle bundle) {
		if (bundle.discogs() == null) {
			throw new DiscogsNotConnectedException();
		}
		return ResponseEntity.ok()
			.contentType(MediaType.APPLICATION_JSON)
			.body(gateway.collectionPage(bundle.discogs(), page).body());
	}

}
