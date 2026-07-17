package shinhan.fibri.ieum.main.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.Locale;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import shinhan.fibri.ieum.common.auth.domain.GenderType;
import shinhan.fibri.ieum.common.auth.domain.User;
import shinhan.fibri.ieum.common.auth.domain.UserSettings;
import shinhan.fibri.ieum.common.auth.repository.UserSettingsRepository;

class UserMailLocaleResolverTest {

	@Test
	void resolvesTheSupportedLanguageStoredInUserSettings() {
		UserSettingsRepository repository = mock(UserSettingsRepository.class);
		when(repository.findById(10L)).thenReturn(Optional.of(UserSettings.forSignup(user(), "ja")));
		UserMailLocaleResolver resolver = new UserMailLocaleResolver(repository);

		assertThat(resolver.resolve(10L)).isEqualTo(Locale.JAPANESE);
	}

	@Test
	void fallsBackToKoreanForMissingOrUnsupportedSettings() {
		UserSettingsRepository repository = mock(UserSettingsRepository.class);
		when(repository.findById(10L)).thenReturn(Optional.empty());
		when(repository.findById(11L)).thenReturn(Optional.of(UserSettings.forSignup(user(), "unsupported")));
		UserMailLocaleResolver resolver = new UserMailLocaleResolver(repository);

		assertThat(resolver.resolve(10L)).isEqualTo(Locale.KOREAN);
		assertThat(resolver.resolve(11L)).isEqualTo(Locale.KOREAN);
	}

	private User user() {
		return User.createEmailUser(
			"user@example.com",
			"hash",
			"user",
			LocalDate.of(2000, 1, 1),
			GenderType.female,
			"KR"
		);
	}
}
