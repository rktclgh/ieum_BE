package shinhan.fibri.ieum.main.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import shinhan.fibri.ieum.common.auth.validation.AuthValidationRules;
import shinhan.fibri.ieum.common.auth.validation.Utf8ByteSize;
import shinhan.fibri.ieum.main.auth.validation.NoProfanity;

import java.time.LocalDate;

public record SignupRequest(
	@NotBlank
	@Email
	String email,

	@NotBlank
	@Size(min = AuthValidationRules.MIN_PASSWORD_LENGTH)
	@Utf8ByteSize(max = AuthValidationRules.MAX_PASSWORD_LENGTH)
	@Pattern(regexp = AuthValidationRules.PASSWORD_SPECIAL_CHARACTER_PATTERN)
	String password,

	@NotBlank
	@Size(
		min = AuthValidationRules.MIN_NICKNAME_LENGTH,
		max = AuthValidationRules.MAX_NICKNAME_LENGTH
	)
	@NoProfanity
	String nickname,

	@NotNull
	@Past
	LocalDate birthDate,

	@NotBlank
	String emailVerificationToken
) {
}
