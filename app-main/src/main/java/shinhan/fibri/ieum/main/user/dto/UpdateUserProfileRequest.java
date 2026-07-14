package shinhan.fibri.ieum.main.user.dto;

import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import shinhan.fibri.ieum.common.auth.validation.AuthValidationRules;
import shinhan.fibri.ieum.main.auth.validation.NoProfanity;

public record UpdateUserProfileRequest(
	@Size(
		min = AuthValidationRules.MIN_NICKNAME_LENGTH,
		max = AuthValidationRules.MAX_NICKNAME_LENGTH,
		message = "Nickname must be between 2 and 50 characters"
	)
	@Pattern(regexp = "(?s).*\\S.*", message = "Nickname must not be blank")
	@NoProfanity
	String nickname,

	@Past
	@jakarta.validation.constraints.Past(message = "Birth date must be in the past")
	LocalDate birthDate,

	@Pattern(regexp = AuthValidationRules.GENDER_PATTERN, message = "Gender is not supported")
	String gender,

	@Pattern(regexp = AuthValidationRules.NATIONALITY_PATTERN, message = "Nationality must be ISO 3166-1 alpha-2")
	String nationality
) {
}
