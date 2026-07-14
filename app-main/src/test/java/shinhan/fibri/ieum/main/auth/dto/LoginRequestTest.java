package shinhan.fibri.ieum.main.auth.dto;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import shinhan.fibri.ieum.common.auth.domain.UserRole;

class LoginRequestTest {

	private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

	@Test
	void validLoginRequestHasNoValidationErrors() {
		LoginRequest request = new LoginRequest("user@example.com", "Passw@rd123");

		assertThat(validator.validate(request)).isEmpty();
	}

	@Test
	void loginRequestRejectsBlankEmail() {
		LoginRequest request = new LoginRequest(" ", "Passw@rd123");

		assertThat(validator.validate(request))
			.anySatisfy(violation -> assertThat(violation.getPropertyPath().toString()).isEqualTo("email"));
	}

	@Test
	void loginRequestRejectsInvalidEmail() {
		LoginRequest request = new LoginRequest("not-email", "Passw@rd123");

		assertThat(validator.validate(request))
			.anySatisfy(violation -> assertThat(violation.getPropertyPath().toString()).isEqualTo("email"));
	}

	@Test
	void loginRequestRejectsBlankPassword() {
		LoginRequest request = new LoginRequest("user@example.com", " ");

		assertThat(validator.validate(request))
			.anySatisfy(violation -> assertThat(violation.getPropertyPath().toString()).isEqualTo("password"));
	}

	@Test
	void loginResponseCarriesSessionUserSummary() {
		LoginResponse response = new LoginResponse(1L, UserRole.user, false);

		assertThat(response.userId()).isEqualTo(1L);
		assertThat(response.role()).isEqualTo(UserRole.user);
		assertThat(response.passwordResetRequired()).isFalse();
	}
}
