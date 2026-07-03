package shinhan.fibri.ieum.main.auth.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SixDigitVerificationCodeGeneratorTest {

	@Test
	void generateReturnsSixDigitNumericCode() {
		VerificationCodeGenerator generator = new SixDigitVerificationCodeGenerator();

		String code = generator.generate();

		assertThat(code).matches("\\d{6}");
	}
}
