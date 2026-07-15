package shinhan.fibri.ieum.main.auth.session;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import shinhan.fibri.ieum.common.auth.domain.UserRole;
import shinhan.fibri.ieum.common.auth.domain.UserStatus;

class AuthSessionTest {

	@Test
	void rejectsNegativeAuthVersion() {
		assertThatThrownBy(() -> new AuthSession(
			"sid-1",
			42L,
			"user@example.com",
			"refresh-hash",
			null,
			UserRole.user,
			UserStatus.active,
			OffsetDateTime.parse("2026-07-03T00:00Z"),
			-1L
		))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("authVersion must be nonnegative");
	}
}
