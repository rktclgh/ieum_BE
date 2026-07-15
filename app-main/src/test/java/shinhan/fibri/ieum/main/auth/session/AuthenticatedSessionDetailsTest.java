package shinhan.fibri.ieum.main.auth.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import shinhan.fibri.ieum.common.auth.domain.UserRole;
import shinhan.fibri.ieum.common.auth.domain.UserStatus;
import shinhan.fibri.ieum.common.auth.principal.AuthenticatedUser;

class AuthenticatedSessionDetailsTest {

	@Test
	void rejectsBlankAndOversizedSessionIds() {
		assertThatThrownBy(() -> new AuthenticatedSessionDetails(" "))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new AuthenticatedSessionDetails("s".repeat(65)))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void redactsSessionIdFromDiagnostics() {
		String sessionId = "private-session-id";

		String diagnostic = new AuthenticatedSessionDetails(sessionId).toString();

		assertThat(diagnostic).doesNotContain(sessionId);
		assertThat(diagnostic).contains("redacted");
	}

	@Test
	void validatedSessionAlsoRedactsSessionIdFromDiagnostics() {
		String sessionId = "private-session-id";
		AuthenticatedUser principal = new AuthenticatedUser(
			42L,
			"user@example.com",
			UserRole.user,
			UserStatus.active
		);

		String diagnostic = new ValidatedAuthSession(principal, sessionId).toString();

		assertThat(diagnostic).doesNotContain(sessionId);
		assertThat(diagnostic).contains("redacted");
	}
}
