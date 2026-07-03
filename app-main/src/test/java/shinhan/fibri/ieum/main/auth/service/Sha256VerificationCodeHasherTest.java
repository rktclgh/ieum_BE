package shinhan.fibri.ieum.main.auth.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class Sha256VerificationCodeHasherTest {

	@Test
	void hashReturnsStableNonPlainTextHash() {
		VerificationCodeHasher hasher = new Sha256VerificationCodeHasher();

		String hash = hasher.hash("123456");

		assertThat(hash).isEqualTo(hasher.hash("123456"));
		assertThat(hash).isNotEqualTo("123456");
		assertThat(hash).hasSize(64);
	}
}
