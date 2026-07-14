package shinhan.fibri.ieum.main.notification.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class InternalAiCallbackTokenVerifier {

	private final byte[] expectedDigest;
	private final boolean configured;

	public InternalAiCallbackTokenVerifier(
		@Value("${app.ai.internal-callback-token:}") String expectedToken
	) {
		String configuredToken = expectedToken == null ? "" : expectedToken;
		this.expectedDigest = sha256(configuredToken);
		this.configured = !configuredToken.isBlank();
	}

	public boolean matches(String suppliedToken) {
		String supplied = suppliedToken == null ? "" : suppliedToken;
		boolean equal = MessageDigest.isEqual(expectedDigest, sha256(supplied));
		return configured & equal;
	}

	private static byte[] sha256(String value) {
		try {
			return MessageDigest.getInstance("SHA-256")
				.digest(value.getBytes(StandardCharsets.UTF_8));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}
}
