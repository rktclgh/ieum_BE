package shinhan.fibri.ieum.main.auth.validation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProfanityNicknameValidatorTest {

	private final ProfanityNicknameValidator validator = new ProfanityNicknameValidator();

	@Test
	void isValidRejectsCommonProfanityAcrossMajorLanguagesAndKorean() {
		assertThat(validator.isValid("fuckmaster", null)).isFalse();
		assertThat(validator.isValid("傻逼用户", null)).isFalse();
		assertThat(validator.isValid("चूतियाUser", null)).isFalse();
		assertThat(validator.isValid("putauser", null)).isFalse();
		assertThat(validator.isValid("كسمكuser", null)).isFalse();
		assertThat(validator.isValid("connarduser", null)).isFalse();
		assertThat(validator.isValid("시발닉", null)).isFalse();
	}

	@Test
	void isValidAllowsOrdinaryNickname() {
		assertThat(validator.isValid("좋은닉네임", null)).isTrue();
		assertThat(validator.isValid("normalUser", null)).isTrue();
	}
}
