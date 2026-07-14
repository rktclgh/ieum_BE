package shinhan.fibri.ieum.main.auth.session;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OpaqueTokenGeneratorTest {

	@Test
	void generateReturnsUrlSafeTokenWithoutPadding() {
		OpaqueTokenGenerator generator = new OpaqueTokenGenerator();

		String token = generator.generate();

		assertThat(token).matches("^[A-Za-z0-9_-]+$");
		assertThat(token).doesNotContain("=");
	}

	@Test
	void generateReturnsDifferentTokens() {
		OpaqueTokenGenerator generator = new OpaqueTokenGenerator();

		assertThat(generator.generate()).isNotEqualTo(generator.generate());
	}
}
