package shinhan.fibri.ieum.main.admin.content.dto;

import jakarta.validation.constraints.NotBlank;

public record HardDeleteContentRequest(
	@NotBlank String confirmationToken
) {
}
