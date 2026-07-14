package shinhan.fibri.ieum.main.auth.dto;

import shinhan.fibri.ieum.common.auth.domain.UserRole;

public record RefreshResponse(
	Long userId,
	UserRole role
) {
}
