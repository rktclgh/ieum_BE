package shinhan.fibri.ieum.main.admin.dto;

import shinhan.fibri.ieum.common.auth.domain.UserRole;

public record AdminLoginResponse(
	Long userId,
	UserRole role,
	boolean passwordResetRequired
) {
}
