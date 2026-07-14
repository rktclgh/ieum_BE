package shinhan.fibri.ieum.main.auth.dto;

import shinhan.fibri.ieum.common.auth.domain.UserRole;

public record LoginResponse(
	Long userId,
	UserRole role,
	boolean passwordResetRequired
) {
}
