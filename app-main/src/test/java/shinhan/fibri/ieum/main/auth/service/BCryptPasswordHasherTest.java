package shinhan.fibri.ieum.main.auth.service;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;

class BCryptPasswordHasherTest {

	@Test
	void hashReturnsNonPlainTextBCryptHash() {
		PasswordHasher passwordHasher = new BCryptPasswordHasher(new BCryptPasswordEncoder());

		String hash = passwordHasher.hash("password123");

		assertThat(hash).isNotEqualTo("password123");
		assertThat(hash).startsWith("$2");
	}
}
