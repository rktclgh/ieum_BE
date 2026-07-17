package shinhan.fibri.ieum.main.mail;

import java.util.Locale;
import org.springframework.stereotype.Component;
import shinhan.fibri.ieum.common.auth.domain.UserSettings;
import shinhan.fibri.ieum.common.auth.repository.UserSettingsRepository;
import shinhan.fibri.ieum.common.auth.validation.AuthValidationRules;

@Component
public class UserMailLocaleResolver {

	private final UserSettingsRepository userSettingsRepository;

	public UserMailLocaleResolver(UserSettingsRepository userSettingsRepository) {
		this.userSettingsRepository = userSettingsRepository;
	}

	public Locale resolve(Long userId) {
		return userSettingsRepository.findById(userId)
			.map(UserSettings::getLanguage)
			.filter(AuthValidationRules.SUPPORTED_LANGUAGES::contains)
			.map(Locale::forLanguageTag)
			.orElse(Locale.KOREAN);
	}
}
