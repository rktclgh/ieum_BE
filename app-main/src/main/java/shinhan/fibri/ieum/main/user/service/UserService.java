package shinhan.fibri.ieum.main.user.service;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import shinhan.fibri.ieum.common.auth.domain.GenderType;
import shinhan.fibri.ieum.common.auth.domain.User;
import shinhan.fibri.ieum.common.auth.domain.UserSettings;
import shinhan.fibri.ieum.common.auth.principal.AuthenticatedUser;
import shinhan.fibri.ieum.common.auth.repository.CountryRepository;
import shinhan.fibri.ieum.common.auth.repository.UserRepository;
import shinhan.fibri.ieum.common.auth.repository.UserSettingsRepository;
import shinhan.fibri.ieum.common.file.domain.File;
import shinhan.fibri.ieum.common.file.repository.FileRepository;
import shinhan.fibri.ieum.common.auth.validation.AuthValidationRules;
import shinhan.fibri.ieum.main.auth.session.RedisAuthSessionStore;
import shinhan.fibri.ieum.main.file.service.FileObjectKeys;
import shinhan.fibri.ieum.main.file.service.FileVariant;
import shinhan.fibri.ieum.main.file.storage.FileStorage;
import shinhan.fibri.ieum.main.user.dto.ProfileImageResponse;
import shinhan.fibri.ieum.main.user.dto.UpdateProfileImageRequest;
import shinhan.fibri.ieum.main.user.dto.UpdateUserLocationRequest;
import shinhan.fibri.ieum.main.user.dto.UpdateUserProfileRequest;
import shinhan.fibri.ieum.main.user.dto.UpdateUserSettingsRequest;
import shinhan.fibri.ieum.main.user.dto.UserMeResponse;
import shinhan.fibri.ieum.main.user.dto.UserSettingsResponse;
import shinhan.fibri.ieum.main.user.exception.InvalidUserFieldException;
import shinhan.fibri.ieum.main.user.exception.NicknameAlreadyUsedException;
import shinhan.fibri.ieum.main.user.exception.UserNotFoundException;

@Service
@RequiredArgsConstructor
public class UserService {

	private static final Logger log = LoggerFactory.getLogger(UserService.class);

	private final UserRepository userRepository;
	private final UserSettingsRepository userSettingsRepository;
	private final CountryRepository countryRepository;
	private final RedisAuthSessionStore sessionStore;
	private final FileRepository fileRepository;
	private final FileStorage fileStorage;

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
		if (request.nationality() != null && !request.nationality().equals(user.getNationality())) {
			validateNationality(request.nationality());
		}

		GenderType gender = request.gender() == null ? user.getGender() : parseGender(request.gender());
		try {
			user.updateProfile(
				nickname,
				request.birthDate() == null ? user.getBirthDate() : request.birthDate(),
				gender,
				nationality
			);
			userRepository.flush();
		} catch (DataIntegrityViolationException exception) {
			throw mapProfileUpdateConstraint(exception);
		}
		return UserMeResponse.of(user, settings);
	}

	@Transactional
	public UserSettingsResponse updateSettings(AuthenticatedUser principal, UpdateUserSettingsRequest request) {
		User user = findActiveUser(principal.userId());
		UserSettings settings = findOrCreateSettings(user);

		String language = request.language() == null ? settings.getLanguage() : request.language();
		validateLanguage(language);
		if (request.hasUnsupportedRadius()) {
			throw new InvalidUserFieldException("notifyRadiusKm", "Notify radius must be one of 3, 5, 10");
		}

		settings.update(
			language,
			request.cameraPermission() == null ? settings.isCameraPermission() : request.cameraPermission(),
			request.pushPermission() == null ? settings.isPushPermission() : request.pushPermission(),
			request.notifyAllEnabled() == null ? settings.isNotifyAll() : request.notifyAllEnabled(),
			request.notifyMeeting() == null ? settings.isNotifyMeeting() : request.notifyMeeting(),
			request.notifyQuestion() == null ? settings.isNotifyQuestion() : request.notifyQuestion(),
			request.notifyRadiusKm() == null ? settings.getNotifyRadiusKm() : request.notifyRadiusKm()
		);
		return UserSettingsResponse.from(settings);
	}

	@Transactional
	public void updateLocation(AuthenticatedUser principal, UpdateUserLocationRequest request) {
		User user = findActiveUser(principal.userId());
		int updatedRows = userRepository.updateLastLocation(user.getId(), request.longitude(), request.latitude());
		if (updatedRows == 0) {
			throw new UserNotFoundException();
		}
	}

	@Transactional
	public ProfileImageResponse updateProfileImage(AuthenticatedUser principal, UpdateProfileImageRequest request) {
		User user = findActiveUser(principal.userId());
		File file = findCompletedOwnedFile(request.fileId(), user.getId());
		UUID previousFileId = user.getProfileFileId();

		user.linkProfileImage(file.getFileId());
		if (previousFileId != null && !Objects.equals(previousFileId, file.getFileId())) {
			deleteProfileFile(previousFileId);
		}

		return new ProfileImageResponse(profileImageUrl(file.getFileId()));
	}

	@Transactional
	public void deleteProfileImage(AuthenticatedUser principal) {
		User user = findActiveUser(principal.userId());
		UUID previousFileId = user.getProfileFileId();
		user.clearProfileImage();
		if (previousFileId != null) {
			deleteProfileFile(previousFileId);
		}
	}

	@Transactional
	public void withdraw(AuthenticatedUser principal) {
		User user = findActiveUser(principal.userId());
		user.markDeleted(OffsetDateTime.now());
		revokeSessionsAfterCommit(user.getId());
	}

	private User findActiveUser(Long userId) {
		return userRepository.findByIdAndDeletedAtIsNull(userId)
			.orElseThrow(UserNotFoundException::new);
	}

	private UserSettings findOrCreateSettings(User user) {
		return userSettingsRepository.findById(user.getId())
			.orElseGet(() -> userSettingsRepository.save(UserSettings.defaultFor(user)));
	}

	private File findCompletedOwnedFile(UUID fileId, Long userId) {
		if (fileId == null) {
			throw new InvalidUserFieldException("fileId", "fileId is required");
		}
		return fileRepository.findByFileIdAndUploaderId(fileId, userId)
			.filter(File::isUploaded)
			.orElseThrow(() -> new InvalidUserFieldException("fileId", "File is not completed or not owned by user"));
	}

	private void deleteProfileFile(UUID fileId) {
		fileRepository.findById(fileId).ifPresent(file -> {
			fileStorage.delete(file.getS3Key());
			fileStorage.delete(FileObjectKeys.variantKey(file.getS3Key(), FileVariant.DISPLAY));
			fileStorage.delete(FileObjectKeys.variantKey(file.getS3Key(), FileVariant.THUMB));
			fileRepository.delete(file);
		});
	}

	private String profileImageUrl(UUID fileId) {
		return "/api/v1/files/" + fileId;
	}

	private void validateNicknameAvailable(String nickname) {
		if (userRepository.existsByNicknameAndDeletedAtIsNull(nickname)) {
			throw new NicknameAlreadyUsedException();
		}
	}

	private RuntimeException mapProfileUpdateConstraint(DataIntegrityViolationException exception) {
		String message = String.valueOf(exception.getMostSpecificCause().getMessage()).toLowerCase();
		if (message.contains("uidx_users_nickname")) {
			return new NicknameAlreadyUsedException();
		}
		return exception;
	}

	private void validateNationality(String nationality) {
		if (!countryRepository.existsByCodeAndIsActiveTrue(nationality)) {
			throw new InvalidUserFieldException("nationality", "Nationality is not supported");
		}
	}

	private void validateLanguage(String language) {
		if (!AuthValidationRules.SUPPORTED_LANGUAGES.contains(language)) {
			throw new InvalidUserFieldException("language", "Language is not supported");
		}
	}

	private GenderType parseGender(String gender) {
		try {
			return GenderType.valueOf(gender);
		} catch (IllegalArgumentException exception) {
			throw new InvalidUserFieldException("gender", "Gender is not supported");
		}
	}

	private void revokeSessionsAfterCommit(Long userId) {
		if (!TransactionSynchronizationManager.isSynchronizationActive()) {
			revokeSessionsLogOnly(userId);
			return;
		}

		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				revokeSessionsLogOnly(userId);
			}
		});
	}

	private void revokeSessionsLogOnly(Long userId) {
		try {
			sessionStore.revokeAllSessionsOfUser(userId);
		} catch (RuntimeException exception) {
			log.warn("Failed to revoke sessions after user withdrawal. userId={}", userId, exception);
		}
	}
}
