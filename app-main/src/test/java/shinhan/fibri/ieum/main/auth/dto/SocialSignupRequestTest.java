package shinhan.fibri.ieum.main.auth.dto;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import shinhan.fibri.ieum.common.auth.domain.UserRole;

class SocialSignupRequestTest {

	private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

	@Test
	void validSocialSignupRequestHasNoValidationErrors() {
		SocialSignupRequest request = validRequest();

		assertThat(validator.validate(request)).isEmpty();
	}

	@Test
	void socialSignupRequestRejectsBlankToken() {
		SocialSignupRequest request = new SocialSignupRequest(
			" ",
			"nickname",
			LocalDate.of(2000, 1, 1),
			"female",
			"KR",
			"ko"
		);

		assertThat(validator.validate(request))
			.anySatisfy(violation -> assertThat(violation.getPropertyPath().toString()).isEqualTo("socialSignupToken"));
	}

	@Test
	void socialSignupRequestRejectsInvalidProfileFields() {
		SocialSignupRequest request = new SocialSignupRequest(
			"signup-token",
			"n",
			LocalDate.of(3000, 1, 1),
			"unknown",
			"kr",
			"de"
		);

		assertThat(validator.validate(request))
			.extracting(violation -> violation.getPropertyPath().toString())
			.contains("nickname", "birthDate", "gender", "nationality", "language");
	}

	@Test
	void responseCarriesCreatedUserSummary() {
		SocialSignupResponse response = new SocialSignupResponse(42L, UserRole.user);

		assertThat(response.userId()).isEqualTo(42L);
		assertThat(response.role()).isEqualTo(UserRole.user);
	}

	private SocialSignupRequest validRequest() {
		return new SocialSignupRequest(
			"signup-token",
			"nickname",
			LocalDate.of(2000, 1, 1),
			"female",
			"KR",
			"ko"
		);
	}
}
