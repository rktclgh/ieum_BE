package shinhan.fibri.ieum.main.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
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
import shinhan.fibri.ieum.main.auth.session.RedisAuthSessionStore;
import shinhan.fibri.ieum.main.friend.service.FriendService;
import shinhan.fibri.ieum.main.user.dto.ProfileImageResponse;
import shinhan.fibri.ieum.main.user.dto.PublicUserProfileResponse;
import shinhan.fibri.ieum.main.user.dto.UpdateProfileImageRequest;
import shinhan.fibri.ieum.main.user.dto.UpdateUserProfileRequest;
import shinhan.fibri.ieum.main.user.dto.UpdateUserSettingsRequest;
import shinhan.fibri.ieum.main.user.dto.UpdateUserLocationRequest;
import shinhan.fibri.ieum.main.user.dto.UserMeResponse;
import shinhan.fibri.ieum.main.user.dto.UserSearchResponse;
import shinhan.fibri.ieum.main.user.dto.UserSettingsResponse;
import shinhan.fibri.ieum.main.user.exception.NicknameAlreadyUsedException;
import shinhan.fibri.ieum.main.user.exception.UserNotFoundException;

class UserServiceTest {

	private final UserRepository userRepository = mock(UserRepository.class);
	private final UserSettingsRepository userSettingsRepository = mock(UserSettingsRepository.class);
	private final CountryRepository countryRepository = mock(CountryRepository.class);
	private final RedisAuthSessionStore sessionStore = mock(RedisAuthSessionStore.class);
	private final FileRepository fileRepository = mock(FileRepository.class);
	private final ProfileFileCleanupService profileFileCleanupService = mock(ProfileFileCleanupService.class);
	private final FriendService friendService = mock(FriendService.class);
	private final UserService service = new UserService(
		userRepository,
		userSettingsRepository,
		countryRepository,
		sessionStore,
		fileRepository,
		profileFileCleanupService,
		friendService
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
		assertThat(response.profileImageUrl()).isNull();
		assertThat(response.settings().language()).isEqualTo("ko");
	}

	@Test
	void getMeReturnsProfileImageUrlWhenProfileFileIdExists() {
		User user = user();
		UUID profileFileId = UUID.fromString("11111111-1111-1111-1111-111111111111");
		user.linkProfileImage(profileFileId);
		UserSettings settings = UserSettings.defaultFor(user);
		when(userRepository.findByIdAndDeletedAtIsNull(42L)).thenReturn(Optional.of(user));
		when(userSettingsRepository.findById(42L)).thenReturn(Optional.of(settings));

		UserMeResponse response = service.getMe(principal());

		assertThat(response.profileImageUrl()).isEqualTo("/api/v1/files/11111111-1111-1111-1111-111111111111");
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
	void updateMeMapsDuplicateNicknameConstraintToConflictException() {
		User user = user();
		UserSettings settings = UserSettings.defaultFor(user);
		when(userRepository.findByIdAndDeletedAtIsNull(42L)).thenReturn(Optional.of(user));
		when(userSettingsRepository.findById(42L)).thenReturn(Optional.of(settings));
		when(userRepository.existsByNicknameAndDeletedAtIsNull("taken")).thenReturn(false);
		doThrow(new DataIntegrityViolationException("uidx_users_nickname"))
			.when(userRepository)
			.flush();

		assertThatThrownBy(() -> service.updateMe(
			principal(),
			new UpdateUserProfileRequest("taken", null, null, null)
		)).isInstanceOf(NicknameAlreadyUsedException.class);
	}

	@Test
	void updateSettingsPreservesOmittedFieldsAndUpdatesProvidedFields() {
		User user = user();
		UserSettings settings = UserSettings.defaultFor(user);
		when(userRepository.findByIdAndDeletedAtIsNull(42L)).thenReturn(Optional.of(user));
		when(userSettingsRepository.findById(42L)).thenReturn(Optional.of(settings));

		UserSettingsResponse response = service.updateSettings(
			principal(),
			new UpdateUserSettingsRequest(null, true, false, null, false, null, 10)
		);

		assertThat(response.language()).isEqualTo("ko");
		assertThat(response.cameraPermission()).isTrue();
		assertThat(response.pushPermission()).isFalse();
		assertThat(response.notifyAllEnabled()).isTrue();
		assertThat(response.notifyMeeting()).isFalse();
		assertThat(response.notifyQuestion()).isTrue();
		assertThat(response.notifyRadiusKm()).isEqualTo(10);
	}

	@Test
	void updateLocationStoresLongitudeLatitudeOrder() {
		User user = user();
		when(userRepository.findByIdAndDeletedAtIsNull(42L)).thenReturn(Optional.of(user));
		when(userRepository.updateLastLocation(42L, 127.0276, 37.4979)).thenReturn(1);

		service.updateLocation(principal(), new UpdateUserLocationRequest(127.0276, 37.4979));

		verify(userRepository).updateLastLocation(42L, 127.0276, 37.4979);
	}

	@Test
	void updateLocationThrowsUserNotFoundWhenNoRowUpdated() {
		User user = user();
		when(userRepository.findByIdAndDeletedAtIsNull(42L)).thenReturn(Optional.of(user));
		when(userRepository.updateLastLocation(42L, 127.0276, 37.4979)).thenReturn(0);

		assertThatThrownBy(() -> service.updateLocation(
			principal(),
			new UpdateUserLocationRequest(127.0276, 37.4979)
		)).isInstanceOf(UserNotFoundException.class);
	}

	@Test
	void updateProfileImageLinksCompletedOwnedFileAndDeletesPreviousProfileFile() {
		User user = user();
		UUID oldFileId = UUID.fromString("22222222-2222-2222-2222-222222222222");
		UUID newFileId = UUID.fromString("33333333-3333-3333-3333-333333333333");
		user.linkProfileImage(oldFileId);
		File newFile = completedFile(newFileId, "final/42/profile/" + newFileId + "/original.png");
		when(userRepository.findByIdAndDeletedAtIsNull(42L)).thenReturn(Optional.of(user));
		when(fileRepository.findByFileIdAndUploaderId(newFileId, 42L)).thenReturn(Optional.of(newFile));
		doNothing().when(profileFileCleanupService).cleanupProfileFile(oldFileId);

		TransactionSynchronizationManager.initSynchronization();
		ProfileImageResponse response;
		try {
			response = service.updateProfileImage(
				principal(),
				new UpdateProfileImageRequest(newFileId)
			);

			assertThat(user.getProfileFileId()).isEqualTo(newFileId);
			assertThat(response.profileImageUrl()).isEqualTo("/api/v1/files/33333333-3333-3333-3333-333333333333");
			verify(profileFileCleanupService, never()).cleanupProfileFile(any());

			TransactionSynchronizationManager.getSynchronizations()
				.forEach(TransactionSynchronization::afterCommit);
		} finally {
			TransactionSynchronizationManager.clearSynchronization();
		}

		verify(profileFileCleanupService).cleanupProfileFile(oldFileId);
	}

	@Test
	void updateProfileImageRejectsPendingOrUnownedFile() {
		User user = user();
		UUID fileId = UUID.fromString("44444444-4444-4444-4444-444444444444");
		when(userRepository.findByIdAndDeletedAtIsNull(42L)).thenReturn(Optional.of(user));
		when(fileRepository.findByFileIdAndUploaderId(fileId, 42L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.updateProfileImage(principal(), new UpdateProfileImageRequest(fileId)))
			.isInstanceOf(shinhan.fibri.ieum.main.user.exception.InvalidUserFieldException.class);
	}

	@Test
	void deleteProfileImageClearsProfileAndDeletesCurrentFile() {
		User user = user();
		UUID fileId = UUID.fromString("55555555-5555-5555-5555-555555555555");
		user.linkProfileImage(fileId);
		when(userRepository.findByIdAndDeletedAtIsNull(42L)).thenReturn(Optional.of(user));
		doNothing().when(profileFileCleanupService).cleanupProfileFile(fileId);

		TransactionSynchronizationManager.initSynchronization();
		try {
			service.deleteProfileImage(principal());

			assertThat(user.getProfileFileId()).isNull();
			verify(profileFileCleanupService, never()).cleanupProfileFile(any());

			TransactionSynchronizationManager.getSynchronizations()
				.forEach(TransactionSynchronization::afterCommit);
		} finally {
			TransactionSynchronizationManager.clearSynchronization();
		}

		verify(profileFileCleanupService).cleanupProfileFile(fileId);
	}

	@Test
	void deleteProfileImageWithoutProfileFileDoesNotScheduleCleanup() {
		User user = user();
		when(userRepository.findByIdAndDeletedAtIsNull(42L)).thenReturn(Optional.of(user));

		TransactionSynchronizationManager.initSynchronization();
		try {
			service.deleteProfileImage(principal());
			TransactionSynchronizationManager.getSynchronizations()
				.forEach(TransactionSynchronization::afterCommit);
		} finally {
			TransactionSynchronizationManager.clearSynchronization();
		}

		verify(profileFileCleanupService, never()).cleanupProfileFile(any());
	}

	@Test
	void withdrawSoftDeletesUserAndRevokesSessions() {
		User user = user();
		when(userRepository.findByIdAndDeletedAtIsNull(42L)).thenReturn(Optional.of(user));

		service.withdraw(principal());

		assertThat(user.getDeletedAt()).isNotNull();
		verify(userRepository, never()).delete(user);
		verify(sessionStore).revokeAllSessionsOfUser(42L);
	}

	@Test
	void withdrawKeepsSoftDeleteWhenSessionRevocationFails() {
		User user = user();
		when(userRepository.findByIdAndDeletedAtIsNull(42L)).thenReturn(Optional.of(user));
		doThrow(new IllegalStateException("redis unavailable"))
			.when(sessionStore)
			.revokeAllSessionsOfUser(42L);

		service.withdraw(principal());

		assertThat(user.getDeletedAt()).isNotNull();
		verify(sessionStore).revokeAllSessionsOfUser(42L);
	}

	@Test
	void missingUserThrowsUserNotFound() {
		when(userRepository.findByIdAndDeletedAtIsNull(42L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.getMe(principal()))
			.isInstanceOf(UserNotFoundException.class);
	}

	@Test
	void searchExcludesSelfAndBlockedUsersAndSetsIsFriend() {
		User currentUser = user();
		User friend = user(7L, "nick-friend", "US");
		User blocked = user(8L, "nick-blocked", "JP");
		User stranger = user(9L, "nick-stranger", "KR");
		setLastActiveAt(friend, OffsetDateTime.parse("2026-07-07T01:00:00Z"));
		when(userRepository.findByIdAndDeletedAtIsNull(42L)).thenReturn(Optional.of(currentUser));
		when(userRepository.searchActiveUsersByNickname(eq("nick"), any(Pageable.class)))
			.thenReturn(List.of(currentUser, friend, blocked, stranger));
		when(friendService.blockedUserIdsOf(42L)).thenReturn(Set.of(8L));
		when(friendService.acceptedFriendIdsOf(42L)).thenReturn(Set.of(7L));

		List<UserSearchResponse> responses = service.searchUsers(principal(), " nick ");

		assertThat(responses).extracting(UserSearchResponse::userId)
			.containsExactly(7L, 9L);
		assertThat(responses.get(0).isFriend()).isTrue();
		assertThat(responses.get(0).lastActiveAt()).isEqualTo(OffsetDateTime.parse("2026-07-07T01:00:00Z"));
		assertThat(responses.get(1).isFriend()).isFalse();
	}

	@Test
	void searchBlankNicknameRejects() {
		assertThatThrownBy(() -> service.searchUsers(principal(), "  "))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("nickname is required");
		verify(userRepository, never()).searchActiveUsersByNickname(any(), any());
	}

	@Test
	void publicProfileReturnsIsFriend() {
		User currentUser = user();
		User targetUser = user(7L, "target", "US");
		when(userRepository.findByIdAndDeletedAtIsNull(42L)).thenReturn(Optional.of(currentUser));
		when(userRepository.findByIdAndDeletedAtIsNull(7L)).thenReturn(Optional.of(targetUser));
		when(friendService.hasBlockBetween(42L, 7L)).thenReturn(false);
		when(friendService.areFriends(42L, 7L)).thenReturn(true);

		PublicUserProfileResponse response = service.getPublicProfile(principal(), 7L);

		assertThat(response.userId()).isEqualTo(7L);
		assertThat(response.nickname()).isEqualTo("target");
		assertThat(response.nationality()).isEqualTo("US");
		assertThat(response.grade()).isEqualTo("bronze");
		assertThat(response.isFriend()).isTrue();
	}

	@Test
	void publicProfileHidesBlockedRelationshipAsUserNotFound() {
		User currentUser = user();
		User targetUser = user(7L, "target", "US");
		when(userRepository.findByIdAndDeletedAtIsNull(42L)).thenReturn(Optional.of(currentUser));
		when(userRepository.findByIdAndDeletedAtIsNull(7L)).thenReturn(Optional.of(targetUser));
		when(friendService.hasBlockBetween(42L, 7L)).thenReturn(true);

		assertThatThrownBy(() -> service.getPublicProfile(principal(), 7L))
			.isInstanceOf(UserNotFoundException.class);
		verify(friendService, never()).areFriends(42L, 7L);
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

	private User user(Long id, String nickname, String nationality) {
		User user = User.createEmailUser(
			nickname + "@example.com",
			"hash",
			nickname,
			LocalDate.of(1995, 5, 20),
			GenderType.female,
			nationality
		);
		setId(user, id);
		return user;
	}

	private File completedFile(UUID fileId, String finalKey) {
		File file = File.pending(fileId, 42L, finalKey, finalKey.endsWith(".png") ? "image/png" : "image/jpeg", 1024L);
		file.markUploaded(OffsetDateTime.parse("2026-07-07T00:00:00Z"), file.getContentType(), 1024L);
		return file;
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

	private void setLastActiveAt(User user, OffsetDateTime lastActiveAt) {
		try {
			java.lang.reflect.Field field = User.class.getDeclaredField("lastActiveAt");
			field.setAccessible(true);
			field.set(user, lastActiveAt);
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException(exception);
		}
	}
}
