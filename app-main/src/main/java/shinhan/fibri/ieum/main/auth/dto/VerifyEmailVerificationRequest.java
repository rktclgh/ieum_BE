package shinhan.fibri.ieum.main.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record VerifyEmailVerificationRequest(
	@NotBlank
	@Email
	String email,

	@NotBlank
	@Pattern(regexp = "\\d{6}")
	String code
) {
}
