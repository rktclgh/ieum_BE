package shinhan.fibri.ieum.main.friend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import shinhan.fibri.ieum.common.auth.domain.GenderType;
import shinhan.fibri.ieum.common.auth.domain.User;
import shinhan.fibri.ieum.common.auth.domain.UserRole;
import shinhan.fibri.ieum.common.auth.domain.UserStatus;
import shinhan.fibri.ieum.common.auth.principal.AuthenticatedUser;
import shinhan.fibri.ieum.common.auth.repository.UserRepository;
import shinhan.fibri.ieum.common.friend.domain.Friendship;
import shinhan.fibri.ieum.common.friend.domain.FriendshipStatus;
import shinhan.fibri.ieum.common.friend.repository.FriendshipRepository;
import shinhan.fibri.ieum.main.friend.exception.AlreadyFriendsException;
import shinhan.fibri.ieum.main.friend.exception.BlockedFriendshipException;
import shinhan.fibri.ieum.main.friend.exception.FriendRequestExistsException;
import shinhan.fibri.ieum.main.friend.exception.SelfFriendRequestException;
import shinhan.fibri.ieum.main.user.exception.UserNotFoundException;

class FriendServiceTest {

	private final UserRepository userRepository = mock(UserRepository.class);
	private final FriendshipRepository friendshipRepository = mock(FriendshipRepository.class);
	private final FriendRequestNotifier friendRequestNotifier = mock(FriendRequestNotifier.class);
	private final FriendService service = new FriendService(
		userRepository,
		friendshipRepository,
		friendRequestNotifier
	);

	@Test
	void requestFriendCreatesPendingFriendshipAndNotifiesWhenBothUsersAreActive() {
		User currentUser = user(42L, "current@example.com", "current");
		User targetUser = user(77L, "target@example.com", "target");
		when(userRepository.findByIdAndDeletedAtIsNull(42L)).thenReturn(Optional.of(currentUser));
		when(userRepository.findByIdAndDeletedAtIsNull(77L)).thenReturn(Optional.of(targetUser));
		when(friendshipRepository.findByUserPair(42L, 77L)).thenReturn(Optional.empty());
		when(friendshipRepository.save(any(Friendship.class))).thenAnswer(invocation -> invocation.getArgument(0));

		service.requestFriend(principal(42L), 77L);

		verify(friendshipRepository).save(any(Friendship.class));
		verify(friendRequestNotifier).notifyRequested(42L, 77L);
	}

	@Test
	void requestFriendNotifiesAfterCommitWhenTransactionSynchronizationIsActive() {
		User currentUser = user(42L, "current@example.com", "current");
		User targetUser = user(77L, "target@example.com", "target");
		when(userRepository.findByIdAndDeletedAtIsNull(42L)).thenReturn(Optional.of(currentUser));
		when(userRepository.findByIdAndDeletedAtIsNull(77L)).thenReturn(Optional.of(targetUser));
		when(friendshipRepository.findByUserPair(42L, 77L)).thenReturn(Optional.empty());
		when(friendshipRepository.save(any(Friendship.class))).thenAnswer(invocation -> invocation.getArgument(0));

		TransactionSynchronizationManager.initSynchronization();
		try {
			service.requestFriend(principal(42L), 77L);

			verify(friendRequestNotifier, never()).notifyRequested(any(), any());

			TransactionSynchronizationManager.getSynchronizations()
				.forEach(TransactionSynchronization::afterCommit);
		} finally {
			TransactionSynchronizationManager.clearSynchronization();
		}

		verify(friendRequestNotifier).notifyRequested(42L, 77L);
	}

	@Test
	void requestFriendStoresRequesterAndAddresseeInSavedFriendship() {
		User currentUser = user(42L, "current@example.com", "current");
		User targetUser = user(77L, "target@example.com", "target");
		when(userRepository.findByIdAndDeletedAtIsNull(42L)).thenReturn(Optional.of(currentUser));
		when(userRepository.findByIdAndDeletedAtIsNull(77L)).thenReturn(Optional.of(targetUser));
		when(friendshipRepository.findByUserPair(42L, 77L)).thenReturn(Optional.empty());
		when(friendshipRepository.save(any(Friendship.class))).thenAnswer(invocation -> {
			Friendship friendship = invocation.getArgument(0);
			assertThat(friendship.getRequester()).isEqualTo(currentUser);
			assertThat(friendship.getAddressee()).isEqualTo(targetUser);
			assertThat(friendship.getStatus()).isEqualTo(FriendshipStatus.pending);
			assertThat(friendship.getBlockedBy()).isNull();
			return friendship;
		});

		service.requestFriend(principal(42L), 77L);
	}

	@Test
	void requestFriendThrowsUserNotFoundWhenCurrentUserIsMissingOrDeleted() {
		when(userRepository.findByIdAndDeletedAtIsNull(42L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.requestFriend(principal(42L), 77L))
			.isInstanceOf(UserNotFoundException.class);

		verify(friendshipRepository, never()).save(any());
		verify(friendRequestNotifier, never()).notifyRequested(42L, 77L);
	}

	@Test
	void requestFriendThrowsUserNotFoundWhenTargetUserIsMissingOrDeleted() {
		User currentUser = user(42L, "current@example.com", "current");
		when(userRepository.findByIdAndDeletedAtIsNull(42L)).thenReturn(Optional.of(currentUser));
		when(userRepository.findByIdAndDeletedAtIsNull(77L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.requestFriend(principal(42L), 77L))
			.isInstanceOf(UserNotFoundException.class);

		verify(friendshipRepository, never()).save(any());
		verify(friendRequestNotifier, never()).notifyRequested(42L, 77L);
	}

	@Test
	void requestFriendRejectsSelfRequest() {
		User currentUser = user(42L, "current@example.com", "current");
		when(userRepository.findByIdAndDeletedAtIsNull(42L)).thenReturn(Optional.of(currentUser));

		assertThatThrownBy(() -> service.requestFriend(principal(42L), 42L))
			.isInstanceOf(SelfFriendRequestException.class);

		verify(friendshipRepository, never()).findByUserPair(any(), any());
		verify(friendshipRepository, never()).save(any());
		verify(friendRequestNotifier, never()).notifyRequested(any(), any());
	}

	@Test
	void requestFriendRejectsExistingPendingRequest() {
		User currentUser = user(42L, "current@example.com", "current");
		User targetUser = user(77L, "target@example.com", "target");
		when(userRepository.findByIdAndDeletedAtIsNull(42L)).thenReturn(Optional.of(currentUser));
		when(userRepository.findByIdAndDeletedAtIsNull(77L)).thenReturn(Optional.of(targetUser));
		when(friendshipRepository.findByUserPair(42L, 77L))
			.thenReturn(Optional.of(Friendship.request(targetUser, currentUser)));

		assertThatThrownBy(() -> service.requestFriend(principal(42L), 77L))
			.isInstanceOf(FriendRequestExistsException.class);

		verify(friendshipRepository, never()).save(any());
		verify(friendRequestNotifier, never()).notifyRequested(any(), any());
	}

	@Test
	void requestFriendRejectsExistingAcceptedFriendship() {
		User currentUser = user(42L, "current@example.com", "current");
		User targetUser = user(77L, "target@example.com", "target");
		Friendship friendship = Friendship.request(currentUser, targetUser);
		friendship.accept();
		when(userRepository.findByIdAndDeletedAtIsNull(42L)).thenReturn(Optional.of(currentUser));
		when(userRepository.findByIdAndDeletedAtIsNull(77L)).thenReturn(Optional.of(targetUser));
		when(friendshipRepository.findByUserPair(42L, 77L)).thenReturn(Optional.of(friendship));

		assertThatThrownBy(() -> service.requestFriend(principal(42L), 77L))
			.isInstanceOf(AlreadyFriendsException.class);

		verify(friendshipRepository, never()).save(any());
		verify(friendRequestNotifier, never()).notifyRequested(any(), any());
	}

	@Test
	void requestFriendRejectsExistingBlockedFriendship() {
		User currentUser = user(42L, "current@example.com", "current");
		User targetUser = user(77L, "target@example.com", "target");
		when(userRepository.findByIdAndDeletedAtIsNull(42L)).thenReturn(Optional.of(currentUser));
		when(userRepository.findByIdAndDeletedAtIsNull(77L)).thenReturn(Optional.of(targetUser));
		when(friendshipRepository.findByUserPair(42L, 77L))
			.thenReturn(Optional.of(Friendship.blocked(currentUser, targetUser, targetUser)));

		assertThatThrownBy(() -> service.requestFriend(principal(42L), 77L))
			.isInstanceOf(BlockedFriendshipException.class);

		verify(friendshipRepository, never()).save(any());
		verify(friendRequestNotifier, never()).notifyRequested(any(), any());
	}

	private AuthenticatedUser principal(Long userId) {
		return new AuthenticatedUser(userId, "user" + userId + "@example.com", UserRole.user, UserStatus.active);
	}

	private User user(Long id, String email, String nickname) {
		User user = User.createEmailUser(
			email,
			"hash",
			nickname,
			LocalDate.of(1995, 1, 1),
			GenderType.female,
			"KR"
		);
		setId(user, id);
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
