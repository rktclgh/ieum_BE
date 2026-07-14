package shinhan.fibri.ieum.main.auth.session;

import java.time.OffsetDateTime;
import shinhan.fibri.ieum.common.auth.domain.UserRole;
import shinhan.fibri.ieum.common.auth.domain.UserStatus;

public record AuthSession(
	String sessionId,
	Long userId,
	String email,
	String refreshTokenHash,
	String prevRefreshTokenHash,
	UserRole role,
	UserStatus status,
	OffsetDateTime createdAt
) {
}
