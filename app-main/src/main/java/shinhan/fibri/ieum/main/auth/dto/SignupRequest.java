package shinhan.fibri.ieum.main.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

public record SignupRequest(
	@NotBlank
	@Email
	String email,

	@NotBlank
	String password,

	@NotBlank
	String nickname,

	@NotNull
	LocalDate birthDate,

	@NotBlank
	String emailVerificationToken
) {
}
