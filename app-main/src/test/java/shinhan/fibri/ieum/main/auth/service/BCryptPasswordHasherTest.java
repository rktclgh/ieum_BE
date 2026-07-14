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

	@Test
	void matchesReturnsTrueForEncodedPassword() {
		PasswordHasher passwordHasher = new BCryptPasswordHasher(new BCryptPasswordEncoder());
		String hash = passwordHasher.hash("Passw@rd123");

		assertThat(passwordHasher.matches("Passw@rd123", hash)).isTrue();
	}

	@Test
	void matchesReturnsFalseForWrongPassword() {
		PasswordHasher passwordHasher = new BCryptPasswordHasher(new BCryptPasswordEncoder());
		String hash = passwordHasher.hash("Passw@rd123");

		assertThat(passwordHasher.matches("Wrong@1234", hash)).isFalse();
	}
}
