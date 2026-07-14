package shinhan.fibri.ieum.main.place.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import shinhan.fibri.ieum.common.auth.domain.UserRole;
import shinhan.fibri.ieum.common.auth.domain.UserStatus;
import shinhan.fibri.ieum.common.auth.principal.AuthenticatedUser;

class PlaceClientKeyFactoryTest {

	private final PlaceClientKeyFactory factory = new PlaceClientKeyFactory();

	@Test
	void usesAuthenticatedUserIdBeforeAnonymousAddress() {
		AuthenticatedUser user = new AuthenticatedUser(42L, "user@example.com", UserRole.user, UserStatus.active);

		assertThat(factory.clientKey(user, "203.0.113.1")).isEqualTo("42");
	}
}
