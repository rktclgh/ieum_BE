package shinhan.fibri.ieum.main.auth.service;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;

@Component
public class SixDigitVerificationCodeGenerator implements VerificationCodeGenerator {

	private static final int CODE_BOUND = 1_000_000;

	private final SecureRandom random = new SecureRandom();

	@Override
	public String generate() {
		return "%06d".formatted(random.nextInt(CODE_BOUND));
	}
}
