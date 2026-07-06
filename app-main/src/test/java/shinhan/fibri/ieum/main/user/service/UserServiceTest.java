package shinhan.fibri.ieum.main.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import shinhan.fibri.ieum.common.auth.domain.GenderType;
import shinhan.fibri.ieum.common.auth.domain.User;
import shinhan.fibri.ieum.common.auth.domain.UserSettings;
import shinhan.fibri.ieum.common.auth.principal.AuthenticatedUser;
import shinhan.fibri.ieum.common.auth.repository.CountryRepository;
import shinhan.fibri.ieum.common.auth.repository.UserRepository;
import shinhan.fibri.ieum.common.auth.repository.UserSettingsRepository;
import shinhan.fibri.ieum.main.user.dto.UpdateUserProfileRequest;
import shinhan.fibri.ieum.main.user.dto.UserMeResponse;
import shinhan.fibri.ieum.main.user.exception.UserNotFoundException;

class UserServiceTest {

	private final UserRepository userRepository = mock(UserRepository.class);
	private final UserSettingsRepository userSettingsRepository = mock(UserSettingsRepository.class);
	private final CountryRepository countryRepository = mock(CountryRepository.class);
	private final UserService service = new UserService(
		userRepository,
		userSettingsRepository,
		countryRepository
	);

	@Test
	void getMeReturnsUserProfileGradeAcceptedCountAndSettings() {
		User user = user();
		UserSettings settings = UserSettings.defaultFor(user);
		when(userRepository.findByIdAndDeletedAtIsNull(42L)).thenReturn(Optional.of(user));
		when(userSettingsRepository.findById(42L)).thenReturn(Optional.of(settings));

		UserMeResponse response = service.getMe(principal());

		assertThat(response.userId()).isEqualTo(42L);
		assertThat(response.email()).isEqualTo("user@example.com");
		assertThat(response.nickname()).isEqualTo("nickname");
		assertThat(response.birthDate()).isEqualTo(LocalDate.of(1995, 5, 20));
		assertThat(response.gender()).isEqualTo("female");
		assertThat(response.nationality()).isEqualTo("KR");
		assertThat(response.grade()).isEqualTo("bronze");
		assertThat(response.acceptedCount()).isZero();
		assertThat(response.settings().language()).isEqualTo("ko");
	}

	@Test
	void getMeCreatesDefaultSettingsWhenMissing() {
		User user = user();
		when(userRepository.findByIdAndDeletedAtIsNull(42L)).thenReturn(Optional.of(user));
		when(userSettingsRepository.findById(42L)).thenReturn(Optional.empty());
		when(userSettingsRepository.save(any(UserSettings.class))).thenAnswer(invocation -> invocation.getArgument(0));

		UserMeResponse response = service.getMe(principal());

		assertThat(response.settings().language()).isEqualTo("ko");
		verify(userSettingsRepository).save(any(UserSettings.class));
	}

	@Test
	void updateMePreservesOmittedFieldsAndUpdatesProvidedFields() {
		User user = user();
		UserSettings settings = UserSettings.defaultFor(user);
		when(userRepository.findByIdAndDeletedAtIsNull(42L)).thenReturn(Optional.of(user));
		when(userSettingsRepository.findById(42L)).thenReturn(Optional.of(settings));
		when(countryRepository.existsByCodeAndIsActiveTrue("US")).thenReturn(true);

		UserMeResponse response = service.updateMe(
			principal(),
			new UpdateUserProfileRequest("updated", null, null, "US")
		);

		assertThat(response.nickname()).isEqualTo("updated");
		assertThat(response.birthDate()).isEqualTo(LocalDate.of(1995, 5, 20));
		assertThat(response.gender()).isEqualTo("female");
		assertThat(response.nationality()).isEqualTo("US");
	}

	@Test
	void missingUserThrowsUserNotFound() {
		when(userRepository.findByIdAndDeletedAtIsNull(42L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.getMe(principal()))
			.isInstanceOf(UserNotFoundException.class);
	}

	private AuthenticatedUser principal() {
		return new AuthenticatedUser(42L, "user@example.com", user().getRole(), user().getStatus());
	}

	private User user() {
		User user = User.createEmailUser(
			"user@example.com",
			"hash",
			"nickname",
			LocalDate.of(1995, 5, 20),
			GenderType.female,
			"KR"
		);
		setId(user, 42L);
		return user;
	}

	private void setId(User user, Long id) {
		try {
			java.lang.reflect.Field field = User.class.getDeclaredField("id");
			field.setAccessible(true);
			field.set(user, id);
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException(exception);
		}
	}
}
