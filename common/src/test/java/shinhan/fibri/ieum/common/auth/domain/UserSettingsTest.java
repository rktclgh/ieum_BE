package shinhan.fibri.ieum.common.auth.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class UserSettingsTest {

	@Test
	void forSignupStoresRequestedLanguageAndInitializesDefaults() {
		User user = User.createEmailUser(
			"user@example.com",
			"hash",
			"nickname",
			LocalDate.of(1995, 5, 20),
			GenderType.female,
			"KR"
		);

		UserSettings settings = UserSettings.forSignup(user, "ko");

		assertThat(settings.getUser()).isEqualTo(user);
		assertThat(settings.getLanguage()).isEqualTo("ko");
		assertThat(settings.isCameraPermission()).isFalse();
		assertThat(settings.isPushPermission()).isTrue();
		assertThat(settings.isNotifyAll()).isTrue();
		assertThat(settings.isNotifyMeeting()).isTrue();
		assertThat(settings.isNotifyQuestion()).isTrue();
		assertThat(settings.getNotifyRadiusKm()).isEqualTo(5);
	}

	@Test
	void updateChangesAllSettings() {
		User user = User.createEmailUser(
			"user@example.com",
			"hash",
			"nickname",
			LocalDate.of(1995, 5, 20),
			GenderType.female,
			"KR"
		);
		UserSettings settings = UserSettings.defaultFor(user);

		settings.update(
			"en",
			true,
			false,
			false,
			false,
			true,
			10
		);

		assertThat(settings.getLanguage()).isEqualTo("en");
		assertThat(settings.isCameraPermission()).isTrue();
		assertThat(settings.isPushPermission()).isFalse();
		assertThat(settings.isNotifyAll()).isFalse();
		assertThat(settings.isNotifyMeeting()).isFalse();
		assertThat(settings.isNotifyQuestion()).isTrue();
		assertThat(settings.getNotifyRadiusKm()).isEqualTo(10);
	}
}
