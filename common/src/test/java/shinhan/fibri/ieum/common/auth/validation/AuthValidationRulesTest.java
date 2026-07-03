package shinhan.fibri.ieum.common.auth.validation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AuthValidationRulesTest {

	@Test
	void exposesCommonAuthValidationBounds() {
		assertThat(AuthValidationRules.MIN_PASSWORD_LENGTH).isEqualTo(10);
		assertThat(AuthValidationRules.MAX_PASSWORD_LENGTH).isEqualTo(72);
		assertThat(AuthValidationRules.PASSWORD_SPECIAL_CHARACTER_PATTERN).isEqualTo(".*[^A-Za-z0-9].*");
		assertThat(AuthValidationRules.MIN_NICKNAME_LENGTH).isEqualTo(2);
		assertThat(AuthValidationRules.MAX_NICKNAME_LENGTH).isEqualTo(50);
	}
}
