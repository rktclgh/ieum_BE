package shinhan.fibri.ieum.main.auth.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class ProfanityNicknameValidator implements ConstraintValidator<NoProfanity, String> {

	private static final Pattern IGNORED_CHARACTER_PATTERN = Pattern.compile("[^\\p{L}\\p{N}]");
	private static final Set<String> NORMALIZED_BLOCKED_TERMS = Set.of(
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
	).stream()
		.map(ProfanityNicknameValidator::normalize)
		.collect(Collectors.toUnmodifiableSet());

	@Override
	public boolean isValid(String value, ConstraintValidatorContext context) {
		if (value == null || value.isBlank()) {
			return true;
		}

		String normalized = normalize(value);
		return NORMALIZED_BLOCKED_TERMS.stream()
			.noneMatch(normalized::contains);
	}

	private static String normalize(String value) {
		String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
			.toLowerCase(Locale.ROOT);
		return IGNORED_CHARACTER_PATTERN.matcher(normalized).replaceAll("");
	}
}
