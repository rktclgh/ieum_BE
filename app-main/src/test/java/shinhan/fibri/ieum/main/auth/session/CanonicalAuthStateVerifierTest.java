package shinhan.fibri.ieum.main.auth.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import shinhan.fibri.ieum.common.auth.domain.UserRole;
import shinhan.fibri.ieum.common.auth.domain.UserStatus;
import shinhan.fibri.ieum.common.auth.repository.UserAuthState;
import shinhan.fibri.ieum.common.auth.repository.UserRepository;

class CanonicalAuthStateVerifierTest {

	@Test
	void returnsCanonicalStateAfterOneMatchingLookup() {
		UserRepository userRepository = mock(UserRepository.class);
		CanonicalAuthStateVerifier verifier = new CanonicalAuthStateVerifier(userRepository);
		AuthSession session = session();
		UserAuthState canonical = new UserAuthState(
			"user@example.com",
			UserRole.user,
			UserStatus.active,
			7L
		);
		when(userRepository.findAuthStateById(42L)).thenReturn(Optional.of(canonical));

		assertThat(verifier.findActiveMatching(session)).hasValue(canonical);
		verify(userRepository).findAuthStateById(42L);
	}

	@ParameterizedTest(name = "canonical mismatch: {0}")
	@MethodSource("mismatchingCanonicalStates")
	void returnsEmptyWhenCanonicalStateDoesNotExactlyMatch(String ignored, UserAuthState canonical) {
		UserRepository userRepository = mock(UserRepository.class);
		CanonicalAuthStateVerifier verifier = new CanonicalAuthStateVerifier(userRepository);
		when(userRepository.findAuthStateById(42L)).thenReturn(Optional.of(canonical));

		assertThat(verifier.findActiveMatching(session())).isEmpty();
		verify(userRepository).findAuthStateById(42L);
	}

	@Test
	void returnsEmptyWhenCanonicalUserDoesNotExist() {
		UserRepository userRepository = mock(UserRepository.class);
		CanonicalAuthStateVerifier verifier = new CanonicalAuthStateVerifier(userRepository);
		when(userRepository.findAuthStateById(42L)).thenReturn(Optional.empty());

		assertThat(verifier.findActiveMatching(session())).isEmpty();
		verify(userRepository).findAuthStateById(42L);
	}

	@Test
	void propagatesDatabaseRuntimeFailure() {
		UserRepository userRepository = mock(UserRepository.class);
		CanonicalAuthStateVerifier verifier = new CanonicalAuthStateVerifier(userRepository);
		when(userRepository.findAuthStateById(42L)).thenThrow(new IllegalStateException("database unavailable"));

		assertThatThrownBy(() -> verifier.findActiveMatching(session()))
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("database unavailable");
		verify(userRepository).findAuthStateById(42L);
	}

	private static Stream<Arguments> mismatchingCanonicalStates() {
		return Stream.of(
			Arguments.of("changed email", new UserAuthState("changed@example.com", UserRole.user, UserStatus.active, 7L)),
			Arguments.of("changed role", new UserAuthState("user@example.com", UserRole.admin, UserStatus.active, 7L)),
			Arguments.of("suspended", new UserAuthState("user@example.com", UserRole.user, UserStatus.suspended, 7L)),
			Arguments.of("changed version", new UserAuthState("user@example.com", UserRole.user, UserStatus.active, 8L))
		);
	}

	private AuthSession session() {
		return new AuthSession(
			"sid-1",
			42L,
			"user@example.com",
			"refresh-hash",
			null,
			UserRole.user,
			UserStatus.active,
			OffsetDateTime.parse("2026-07-03T00:00Z"),
			7L
		);
	}
}
