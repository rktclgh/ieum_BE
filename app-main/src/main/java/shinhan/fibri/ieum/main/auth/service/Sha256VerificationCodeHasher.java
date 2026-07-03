package shinhan.fibri.ieum.main.auth.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

@Component
public class Sha256VerificationCodeHasher implements VerificationCodeHasher {

	private static final String HMAC_ALGORITHM = "HmacSHA256";

	private final SecretKeySpec secretKeySpec;

	public Sha256VerificationCodeHasher(
		@Value("${app.auth.email-verification-hmac-secret}") String secret
	) {
		this.secretKeySpec = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM);
	}

	@Override
	public String hash(String email, String code) {
		try {
			Mac mac = Mac.getInstance(HMAC_ALGORITHM);
			mac.init(secretKeySpec);
			byte[] hash = mac.doFinal((email + ":" + code).getBytes(StandardCharsets.UTF_8));
			return toHex(hash);
		} catch (InvalidKeyException | NoSuchAlgorithmException exception) {
			throw new IllegalStateException("HMAC-SHA256 algorithm is unavailable", exception);
		}
	}

	private String toHex(byte[] bytes) {
		StringBuilder builder = new StringBuilder(bytes.length * 2);
		for (byte value : bytes) {
			builder.append("%02x".formatted(value));
		}
		return builder.toString();
	}
}
