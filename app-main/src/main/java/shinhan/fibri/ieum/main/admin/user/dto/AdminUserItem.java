package shinhan.fibri.ieum.main.admin.user.dto;

import java.time.OffsetDateTime;
import shinhan.fibri.ieum.common.auth.domain.AuthProvider;
import shinhan.fibri.ieum.common.auth.domain.UserGrade;
import shinhan.fibri.ieum.common.auth.domain.UserRole;
import shinhan.fibri.ieum.common.auth.domain.UserStatus;

public record AdminUserItem(
	Long userId,
	String email,
	String nickname,
	UserRole role,
	UserStatus status,
	UserGrade grade,
	AuthProvider provider,
	OffsetDateTime lastActiveAt
) {
}
