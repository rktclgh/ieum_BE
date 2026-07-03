package shinhan.fibri.ieum.main.auth.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;

public class ProfanityNicknameValidator implements ConstraintValidator<NoProfanity, String> {

	private static final Set<String> BLOCKED_TERMS = Set.of(
		// English
		"fuck", "shit", "bitch", "cunt", "dickhead",
		// Mandarin Chinese
		"傻逼", "操你妈", "妈的", "狗娘养",
		// Hindi
		"चूतिया", "भोसड़ी", "मादरचोद", "बहनचोद",
		// Spanish
		"puta", "puto", "pendejo", "cabron", "cabrón", "mierda", "joder",
		// Arabic
		"كس", "كسمك", "شرموط", "عرص", "خرا",
		// French
		"merde", "connard", "conne", "putain", "salope",
		// Korean
		"시발", "씨발", "ㅅㅂ", "병신", "개새끼", "좆", "존나"
	);

	@Override
	public boolean isValid(String value, ConstraintValidatorContext context) {
		if (value == null || value.isBlank()) {
			return true;
		}

		String normalized = normalize(value);
		return BLOCKED_TERMS.stream()
			.map(ProfanityNicknameValidator::normalize)
			.noneMatch(normalized::contains);
	}

	private static String normalize(String value) {
		String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
			.toLowerCase(Locale.ROOT);
		return normalized.replaceAll("[^\\p{L}\\p{N}]", "");
	}
}
