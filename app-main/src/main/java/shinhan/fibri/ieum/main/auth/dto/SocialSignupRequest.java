package shinhan.fibri.ieum.main.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import shinhan.fibri.ieum.common.auth.validation.AuthValidationRules;
import shinhan.fibri.ieum.main.auth.validation.NoProfanity;

public record SocialSignupRequest(
	@NotBlank
	String socialSignupToken,

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
	@Pattern(regexp = AuthValidationRules.GENDER_PATTERN)
	String gender,

	@NotBlank
	@Pattern(regexp = AuthValidationRules.NATIONALITY_PATTERN)
	String nationality,

	@NotBlank
	@Pattern(regexp = AuthValidationRules.SUPPORTED_LANGUAGE_PATTERN)
	String language
) {
}
