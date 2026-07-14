package shinhan.fibri.ieum.common.auth.principal;

import java.util.Objects;
import shinhan.fibri.ieum.common.auth.domain.UserRole;
import shinhan.fibri.ieum.common.auth.domain.UserStatus;

public record AuthenticatedUser(
		Long userId,
		String email,
		UserRole role,
		UserStatus status
) {

	public AuthenticatedUser {
		Objects.requireNonNull(userId, "userId must not be null");
		Objects.requireNonNull(email, "email must not be null");
		Objects.requireNonNull(role, "role must not be null");
		Objects.requireNonNull(status, "status must not be null");
	}
}
