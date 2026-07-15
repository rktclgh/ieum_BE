package shinhan.fibri.ieum.main.admin.user.dto;

import jakarta.validation.constraints.NotBlank;

public record HardDeleteUserRequest(
	@NotBlank String confirmationEmail
) {
}
