package com.scrobblecue.credentials;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

class CredentialServiceTests {

	private static final String KEY_1 = "k1:AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";

	private static final String KEY_2 = "k2:ICEiIyQlJicoKSorLC0uLzAxMjM0NTY3ODk6Ozw9Pj8=";

	private static final Instant NOW = Instant.parse("2026-10-27T19:00:00Z");

	private final CredentialService service = service(KEY_1, Clock.fixed(NOW, ZoneOffset.UTC));

	@Test
	void sealedBundleOpensToTheSameContent() {
		var bundle = CredentialBundle.ofLastFm("marin", "session-key", NOW);

		var opened = service.open(service.seal(bundle));

		assertThat(opened).usingRecursiveComparison().isEqualTo(bundle);
	}

	@Test
	void sealedBundleDoesNotContainTheTokenInPlainText() {
		String sealed = service.seal(CredentialBundle.ofLastFm("marin", "session-key", NOW));

		assertThat(sealed).startsWith("k1.").doesNotContain("session-key").doesNotContain("marin");
	}

	@Test
	void tamperedBundleIsRejected() {
		String sealed = service.seal(CredentialBundle.ofLastFm("marin", "session-key", NOW));
		// Change a character in the middle; the last base64 character may only carry unused padding bits
		int i = sealed.length() / 2;
		char replacement = sealed.charAt(i) == 'A' ? 'B' : 'A';
		String tampered = sealed.substring(0, i) + replacement + sealed.substring(i + 1);

		assertThatExceptionOfType(InvalidSealException.class).isThrownBy(() -> service.open(tampered));
	}

	@Test
	void stateCannotBeUsedAsBundle() {
		String state = service.newState("lastfm", ClientType.WEB);

		assertThatExceptionOfType(InvalidSealException.class).isThrownBy(() -> service.open(state));
	}

	@Test
	void stateOpensForItsOwnProviderBeforeExpiry() {
		String sealed = service.newState("lastfm", ClientType.ANDROID);

		var state = service.openState(sealed, "lastfm");

		assertThat(state.client()).isEqualTo(ClientType.ANDROID);
		assertThatExceptionOfType(InvalidSealException.class).isThrownBy(() -> service.openState(sealed, "discogs"));
	}

	@Test
	void expiredStateIsRejected() {
		String sealed = service.newState("lastfm", ClientType.WEB);
		var later = service(KEY_1, Clock.fixed(NOW.plus(Duration.ofMinutes(11)), ZoneOffset.UTC));

		// Still genuine, so it can say where to send the user back to
		assertThatExceptionOfType(StateExpiredException.class).isThrownBy(() -> later.openState(sealed, "lastfm"))
			.satisfies(ex -> assertThat(ex.client()).isEqualTo(ClientType.WEB))
			.satisfies(ex -> assertThat(ex.provider()).isEqualTo("lastfm"));
	}

	@Test
	void bundlesSealedBeforeKeyRotationStillOpen() {
		String sealedWithOldKey = service.seal(CredentialBundle.ofLastFm("marin", "session-key", NOW));
		var rotated = service(KEY_2 + "," + KEY_1, Clock.fixed(NOW, ZoneOffset.UTC));

		assertThat(rotated.open(sealedWithOldKey).lastfm().user()).isEqualTo("marin");
		assertThat(rotated.seal(CredentialBundle.ofLastFm("marin", "session-key", NOW))).startsWith("k2.");
	}

	@Test
	void bundleSealedWithRemovedKeyIsRejected() {
		String sealedWithOldKey = service.seal(CredentialBundle.ofLastFm("marin", "session-key", NOW));
		var onlyNewKey = service(KEY_2, Clock.fixed(NOW, ZoneOffset.UTC));

		assertThatExceptionOfType(InvalidSealException.class).isThrownBy(() -> onlyNewKey.open(sealedWithOldKey));
	}

	@Test
	void missingOrShortKeysFailAtStartup() {
		assertThatIllegalStateException().isThrownBy(() -> new Sealer(new CredentialProperties("")));
		assertThatIllegalStateException().isThrownBy(() -> new Sealer(new CredentialProperties("k1:c2hvcnQ=")));
	}

	private static CredentialService service(String keys, Clock clock) {
		return new CredentialService(new Sealer(new CredentialProperties(keys)), JsonMapper.builder().build(), clock);
	}

}
