package shinhan.fibri.ieum.main.auth.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class Sha256VerificationCodeHasherTest {

	private static final String SECRET_ONE = "01234567890123456789012345678901";
	private static final String SECRET_TWO = "abcdefghijklmnopqrstuvwxyz123456";

	@Test
	void hashReturnsStableNonPlainTextHash() {
		VerificationCodeHasher hasher = new Sha256VerificationCodeHasher(SECRET_ONE);

		String hash = hasher.hash("user@example.com", "123456");

		assertThat(hash).isEqualTo(hasher.hash("user@example.com", "123456"));
		assertThat(hash).isNotEqualTo("123456");
		assertThat(hash).hasSize(64);
	}

	@Test
	void hashUsesEmailContextForSameCode() {
		VerificationCodeHasher hasher = new Sha256VerificationCodeHasher(SECRET_ONE);

		String userHash = hasher.hash("user@example.com", "123456");
		String otherUserHash = hasher.hash("other@example.com", "123456");

		assertThat(userHash).isNotEqualTo(otherUserHash);
	}

	@Test
	void hashUsesServerSecretForSameEmailAndCode() {
		VerificationCodeHasher firstHasher = new Sha256VerificationCodeHasher(SECRET_ONE);
		VerificationCodeHasher secondHasher = new Sha256VerificationCodeHasher(SECRET_TWO);

		assertThat(firstHasher.hash("user@example.com", "123456"))
			.isNotEqualTo(secondHasher.hash("user@example.com", "123456"));
	}
}
