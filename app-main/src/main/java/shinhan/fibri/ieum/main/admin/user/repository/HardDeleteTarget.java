package shinhan.fibri.ieum.main.admin.user.repository;

import shinhan.fibri.ieum.common.auth.domain.UserRole;

public record HardDeleteTarget(
	Long userId,
	String email,
	UserRole role
) {
}
