package shinhan.fibri.ieum.common.auth.validation;

import java.util.List;

public final class AuthValidationRules {

	public static final int MIN_PASSWORD_LENGTH = 10;
	public static final int MAX_PASSWORD_LENGTH = 72;
	public static final String PASSWORD_SPECIAL_CHARACTER_PATTERN = ".*[^A-Za-z0-9].*";
	public static final String GENDER_PATTERN = "^(male|female|other)$";
	public static final int MIN_NICKNAME_LENGTH = 2;
	public static final int MAX_NICKNAME_LENGTH = 50;
	public static final String NATIONALITY_PATTERN = "^[A-Z]{2}$";
	public static final String SUPPORTED_LANGUAGE_PATTERN = "^(ko|en|ja|zh|vi|th|ru)$";
	public static final List<String> SUPPORTED_LANGUAGES = List.of("ko", "en", "ja", "zh", "vi", "th", "ru");

	private AuthValidationRules() {
	}
}
