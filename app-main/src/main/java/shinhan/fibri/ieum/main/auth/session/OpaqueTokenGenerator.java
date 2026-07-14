package shinhan.fibri.ieum.main.auth.session;

import java.security.SecureRandom;
import java.util.Base64;

public class OpaqueTokenGenerator {

	private static final int TOKEN_BYTES = 32;

	private final SecureRandom secureRandom = new SecureRandom();

	public String generate() {
		byte[] bytes = new byte[TOKEN_BYTES];
		secureRandom.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}
}
