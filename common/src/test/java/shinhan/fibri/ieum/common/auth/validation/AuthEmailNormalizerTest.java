package shinhan.fibri.ieum.common.auth.validation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class AuthEmailNormalizerTest {

	@Test
	void normalizeTrimsAndLowercasesEmail() {
		assertThat(AuthEmailNormalizer.normalize("  Member@Example.COM  "))
			.isEqualTo("member@example.com");
	}

	@Test
	void normalizeRejectsNullEmail() {
		assertThatNullPointerException()
			.isThrownBy(() -> AuthEmailNormalizer.normalize(null))
			.withMessage("email must not be null");
	}
}
