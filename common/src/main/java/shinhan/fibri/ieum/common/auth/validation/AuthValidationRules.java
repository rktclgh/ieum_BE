package shinhan.fibri.ieum.common.auth.validation;

public final class AuthValidationRules {

	public static final int MIN_PASSWORD_LENGTH = 10;
	public static final String PASSWORD_SPECIAL_CHARACTER_PATTERN = ".*[^A-Za-z0-9].*";
	public static final int MIN_NICKNAME_LENGTH = 2;
	public static final int MAX_NICKNAME_LENGTH = 50;

	private AuthValidationRules() {
	}
}
