package shinhan.fibri.ieum.main.user.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import shinhan.fibri.ieum.common.auth.domain.GenderType;
import shinhan.fibri.ieum.common.auth.domain.User;
import shinhan.fibri.ieum.common.auth.domain.UserSettings;
import shinhan.fibri.ieum.common.auth.principal.AuthenticatedUser;
import shinhan.fibri.ieum.common.auth.repository.CountryRepository;
import shinhan.fibri.ieum.common.auth.repository.UserRepository;
import shinhan.fibri.ieum.common.auth.repository.UserSettingsRepository;
import shinhan.fibri.ieum.main.user.dto.UpdateUserProfileRequest;
import shinhan.fibri.ieum.main.user.dto.UserMeResponse;
import shinhan.fibri.ieum.main.user.exception.InvalidUserFieldException;
import shinhan.fibri.ieum.main.user.exception.NicknameAlreadyUsedException;
import shinhan.fibri.ieum.main.user.exception.UserNotFoundException;

@Service
@RequiredArgsConstructor
public class UserService {

	private final UserRepository userRepository;
	private final UserSettingsRepository userSettingsRepository;
	private final CountryRepository countryRepository;

	@Transactional
	public UserMeResponse getMe(AuthenticatedUser principal) {
		User user = findActiveUser(principal.userId());
		UserSettings settings = findOrCreateSettings(user);
		return UserMeResponse.of(user, settings);
	}

	@Transactional
	public UserMeResponse updateMe(AuthenticatedUser principal, UpdateUserProfileRequest request) {
		User user = findActiveUser(principal.userId());
		UserSettings settings = findOrCreateSettings(user);

		String nickname = request.nickname() == null ? user.getNickname() : request.nickname();
		if (request.nickname() != null && !request.nickname().equals(user.getNickname())) {
			validateNicknameAvailable(request.nickname());
		}

		String nationality = request.nationality() == null ? user.getNationality() : request.nationality();
		if (request.nationality() != null) {
			validateNationality(request.nationality());
		}

		GenderType gender = request.gender() == null ? user.getGender() : parseGender(request.gender());
		user.updateProfile(
			nickname,
			request.birthDate() == null ? user.getBirthDate() : request.birthDate(),
			gender,
			nationality
		);
		return UserMeResponse.of(user, settings);
	}

	private User findActiveUser(Long userId) {
		return userRepository.findByIdAndDeletedAtIsNull(userId)
			.orElseThrow(UserNotFoundException::new);
	}

	private UserSettings findOrCreateSettings(User user) {
		return userSettingsRepository.findById(user.getId())
			.orElseGet(() -> userSettingsRepository.save(UserSettings.defaultFor(user)));
	}

	private void validateNicknameAvailable(String nickname) {
		if (userRepository.existsByNicknameAndDeletedAtIsNull(nickname)) {
			throw new NicknameAlreadyUsedException();
		}
	}

	private void validateNationality(String nationality) {
		if (!countryRepository.existsByCodeAndIsActiveTrue(nationality)) {
			throw new InvalidUserFieldException("nationality", "Nationality is not supported");
		}
	}

	private GenderType parseGender(String gender) {
		try {
			return GenderType.valueOf(gender);
		} catch (IllegalArgumentException exception) {
			throw new InvalidUserFieldException("gender", "Gender is not supported");
		}
	}
}
