package shinhan.fibri.ieum.common.auth.validation;

import java.util.Locale;
import java.util.Objects;

public final class AuthEmailNormalizer {

	private AuthEmailNormalizer() {
	}

	public static String normalize(String email) {
		return Objects.requireNonNull(email, "email must not be null").trim().toLowerCase(Locale.ROOT);
	}
}
