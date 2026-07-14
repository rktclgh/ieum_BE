package shinhan.fibri.ieum.main.auth.session;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class AuthSecretValidatorTest {

	@Test
	void requireAtLeast32BytesRejectsBlankSecret() {
		assertThatThrownBy(() -> AuthSecretValidator.requireAtLeast32Bytes("", "app.jwt.secret"))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("app.jwt.secret");
	}

	@Test
	void requireAtLeast32BytesRejectsShortSecret() {
		assertThatThrownBy(() -> AuthSecretValidator.requireAtLeast32Bytes("0123456789012345678901234567890", "app.jwt.secret"))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("at least 32 bytes");
	}

	@Test
	void requireAtLeast32BytesAcceptsStrongSecret() {
		assertThatCode(() -> AuthSecretValidator.requireAtLeast32Bytes("01234567890123456789012345678901", "app.jwt.secret"))
			.doesNotThrowAnyException();
	}
}
