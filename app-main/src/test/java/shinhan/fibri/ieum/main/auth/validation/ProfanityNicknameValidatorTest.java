package shinhan.fibri.ieum.main.auth.validation;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.util.Set;
import java.util.regex.Pattern;

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

	@Test
	void validatorCachesNormalizedBlockedTermsAndCompiledPattern() throws Exception {
		var normalizedTermsField = ProfanityNicknameValidator.class.getDeclaredField("NORMALIZED_BLOCKED_TERMS");
		normalizedTermsField.setAccessible(true);
		int normalizedTermsModifiers = normalizedTermsField.getModifiers();

		assertThat(Modifier.isStatic(normalizedTermsModifiers)).isTrue();
		assertThat(Modifier.isFinal(normalizedTermsModifiers)).isTrue();
		@SuppressWarnings("unchecked")
		Set<String> normalizedTerms = (Set<String>) normalizedTermsField.get(null);
		assertThat(normalizedTerms).contains("fuck", "시발");

		var ignoredCharacterPatternField =
			ProfanityNicknameValidator.class.getDeclaredField("IGNORED_CHARACTER_PATTERN");
		ignoredCharacterPatternField.setAccessible(true);
		int patternModifiers = ignoredCharacterPatternField.getModifiers();

		assertThat(Modifier.isStatic(patternModifiers)).isTrue();
		assertThat(Modifier.isFinal(patternModifiers)).isTrue();
		assertThat(ignoredCharacterPatternField.get(null)).isInstanceOf(Pattern.class);
	}
}
