package shinhan.fibri.ieum.main.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import shinhan.fibri.ieum.common.auth.validation.AuthValidationRules;
import shinhan.fibri.ieum.main.auth.validation.NoProfanity;

public record CheckNicknameDuplicateRequest(
	@NotBlank
	@Size(
		min = AuthValidationRules.MIN_NICKNAME_LENGTH,
		max = AuthValidationRules.MAX_NICKNAME_LENGTH
	)
	@NoProfanity
	String nickname
) {
}
