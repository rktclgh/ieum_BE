package shinhan.fibri.ieum.main.notification.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class InternalAiCallbackTokenVerifierTest {

	@Test
	void acceptsOnlyTheConfiguredToken() {
		InternalAiCallbackTokenVerifier verifier = new InternalAiCallbackTokenVerifier("expected-token");

		assertThat(verifier.matches("expected-token")).isTrue();
		assertThat(verifier.matches("wrong-token")).isFalse();
		assertThat(verifier.matches(null)).isFalse();
	}

	@Test
	void blankConfigurationNeverAuthenticates() {
		InternalAiCallbackTokenVerifier verifier = new InternalAiCallbackTokenVerifier(" ");

		assertThat(verifier.matches(" ")).isFalse();
		assertThat(verifier.matches(null)).isFalse();
	}
}
