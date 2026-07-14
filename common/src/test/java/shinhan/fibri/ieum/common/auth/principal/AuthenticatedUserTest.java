package shinhan.fibri.ieum.common.auth.principal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.Test;
import shinhan.fibri.ieum.common.auth.domain.UserRole;
import shinhan.fibri.ieum.common.auth.domain.UserStatus;

class AuthenticatedUserTest {

	@Test
	void exposesOnlyMinimalAuthenticatedUserFields() {
		AuthenticatedUser principal = new AuthenticatedUser(
				1L,
				"member@example.com",
				UserRole.user,
				UserStatus.active
		);

		assertThat(principal.userId()).isEqualTo(1L);
		assertThat(principal.email()).isEqualTo("member@example.com");
		assertThat(principal.role()).isEqualTo(UserRole.user);
		assertThat(principal.status()).isEqualTo(UserStatus.active);
	}

	@Test
	void requiresAllPrincipalFields() {
		assertThatNullPointerException()
				.isThrownBy(() -> new AuthenticatedUser(null, "member@example.com", UserRole.user, UserStatus.active));
		assertThatNullPointerException()
				.isThrownBy(() -> new AuthenticatedUser(1L, null, UserRole.user, UserStatus.active));
		assertThatNullPointerException()
				.isThrownBy(() -> new AuthenticatedUser(1L, "member@example.com", null, UserStatus.active));
		assertThatNullPointerException()
				.isThrownBy(() -> new AuthenticatedUser(1L, "member@example.com", UserRole.user, null));
	}
}
