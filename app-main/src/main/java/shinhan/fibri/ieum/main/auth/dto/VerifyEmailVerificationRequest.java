package shinhan.fibri.ieum.main.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record VerifyEmailVerificationRequest(
	@NotBlank
	@Email
	String email,

	@NotBlank
	String code
) {
}
