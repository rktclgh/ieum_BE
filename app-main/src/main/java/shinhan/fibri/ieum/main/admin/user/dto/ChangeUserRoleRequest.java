package shinhan.fibri.ieum.main.admin.user.dto;

import jakarta.validation.constraints.NotNull;
import shinhan.fibri.ieum.common.auth.domain.UserRole;

public record ChangeUserRoleRequest(
	@NotNull
	UserRole role
) {
}
