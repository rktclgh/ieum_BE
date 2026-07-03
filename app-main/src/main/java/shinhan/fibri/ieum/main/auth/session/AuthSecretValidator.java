package shinhan.fibri.ieum.main.auth.session;

import java.nio.charset.StandardCharsets;

public final class AuthSecretValidator {

	private static final int MIN_BYTES = 32;

	private AuthSecretValidator() {
	}

	public static String requireAtLeast32Bytes(String secret, String propertyName) {
		if (secret == null || secret.isBlank()) {
			throw new IllegalStateException(propertyName + " must be configured");
		}
		if (secret.getBytes(StandardCharsets.UTF_8).length < MIN_BYTES) {
			throw new IllegalStateException(propertyName + " must be at least 32 bytes");
		}
		return secret;
	}
}
