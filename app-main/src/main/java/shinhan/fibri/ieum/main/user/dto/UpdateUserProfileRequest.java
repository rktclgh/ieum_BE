package shinhan.fibri.ieum.main.user.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import shinhan.fibri.ieum.common.auth.validation.AuthValidationRules;

public record UpdateUserProfileRequest(
	@Size(
		min = AuthValidationRules.MIN_NICKNAME_LENGTH,
		max = AuthValidationRules.MAX_NICKNAME_LENGTH,
		message = "Nickname must be between 2 and 50 characters"
	)
	String nickname,

	LocalDate birthDate,

	@Pattern(regexp = AuthValidationRules.GENDER_PATTERN, message = "Gender is not supported")
	String gender,

	@Pattern(regexp = AuthValidationRules.NATIONALITY_PATTERN, message = "Nationality must be ISO 3166-1 alpha-2")
	String nationality
) {
}
