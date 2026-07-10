package shinhan.fibri.ieum.main.admin.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import shinhan.fibri.ieum.main.admin.user.domain.SanctionType;

public record CreateSanctionRequest(
	@NotNull
	SanctionType type,
	@NotBlank
	@Size(max = 500)
	String reason,
	OffsetDateTime endsAt
) {
}
