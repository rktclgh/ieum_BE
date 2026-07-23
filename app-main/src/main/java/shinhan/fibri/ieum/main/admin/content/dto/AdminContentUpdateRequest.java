package shinhan.fibri.ieum.main.admin.content.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AdminContentUpdateRequest(
	@NotBlank @Size(max = 200) String title,
	@NotBlank @Size(max = 5000) String content
) {
}
