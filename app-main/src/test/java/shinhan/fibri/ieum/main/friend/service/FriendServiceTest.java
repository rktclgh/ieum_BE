package shinhan.fibri.ieum.main.friend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
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
import shinhan.fibri.ieum.main.friend.dto.BlockedUserIdsResponse;
import shinhan.fibri.ieum.main.friend.dto.BlockedUserResponse;
import shinhan.fibri.ieum.main.friend.dto.FriendRequestResponse;
import shinhan.fibri.ieum.main.friend.dto.FriendResponse;
import shinhan.fibri.ieum.main.friend.exception.AlreadyFriendsException;
import shinhan.fibri.ieum.main.friend.exception.BlockedFriendshipException;
import shinhan.fibri.ieum.main.friend.exception.CannotAcceptOwnFriendRequestException;
import shinhan.fibri.ieum.main.friend.exception.FriendRequestExistsException;
import shinhan.fibri.ieum.main.friend.exception.FriendshipNotFoundException;
import shinhan.fibri.ieum.main.friend.exception.SelfFriendActionException;
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

	@Test
	void acceptFriendRequestAcceptsPendingRequestWhenCurrentUserIsAddressee() {
		User currentUser = user(42L, "current@example.com", "current");
		User targetUser = user(77L, "target@example.com", "target");
		Friendship friendship = Friendship.request(targetUser, currentUser);
		when(userRepository.findByIdAndDeletedAtIsNull(42L)).thenReturn(Optional.of(currentUser));
		when(userRepository.findByIdAndDeletedAtIsNull(77L)).thenReturn(Optional.of(targetUser));
		when(friendshipRepository.findByUserPair(42L, 77L)).thenReturn(Optional.of(friendship));

		service.acceptFriendRequest(principal(42L), 77L);

		assertThat(friendship.getStatus()).isEqualTo(FriendshipStatus.accepted);
	}

	@Test
	void acceptFriendRequestThrowsUserNotFoundWhenCurrentUserIsMissingOrDeleted() {
		when(userRepository.findByIdAndDeletedAtIsNull(42L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.acceptFriendRequest(principal(42L), 77L))
			.isInstanceOf(UserNotFoundException.class);

		verify(friendshipRepository, never()).findByUserPair(any(), any());
	}

	@Test
	void acceptFriendRequestThrowsUserNotFoundWhenTargetUserIsMissingOrDeleted() {
		User currentUser = user(42L, "current@example.com", "current");
		when(userRepository.findByIdAndDeletedAtIsNull(42L)).thenReturn(Optional.of(currentUser));
		when(userRepository.findByIdAndDeletedAtIsNull(77L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.acceptFriendRequest(principal(42L), 77L))
			.isInstanceOf(UserNotFoundException.class);

		verify(friendshipRepository, never()).findByUserPair(any(), any());
	}

	@Test
	void acceptFriendRequestRejectsSelfAction() {
		User currentUser = user(42L, "current@example.com", "current");
		when(userRepository.findByIdAndDeletedAtIsNull(42L)).thenReturn(Optional.of(currentUser));

		assertThatThrownBy(() -> service.acceptFriendRequest(principal(42L), 42L))
			.isInstanceOf(SelfFriendActionException.class);

		verify(friendshipRepository, never()).findByUserPair(any(), any());
	}

	@Test
	void acceptFriendRequestThrowsFriendshipNotFoundWhenRelationDoesNotExist() {
		User currentUser = user(42L, "current@example.com", "current");
		User targetUser = user(77L, "target@example.com", "target");
		when(userRepository.findByIdAndDeletedAtIsNull(42L)).thenReturn(Optional.of(currentUser));
		when(userRepository.findByIdAndDeletedAtIsNull(77L)).thenReturn(Optional.of(targetUser));
		when(friendshipRepository.findByUserPair(42L, 77L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.acceptFriendRequest(principal(42L), 77L))
			.isInstanceOf(FriendshipNotFoundException.class);
	}

	@Test
	void acceptFriendRequestRejectsOwnSentPendingRequest() {
		User currentUser = user(42L, "current@example.com", "current");
		User targetUser = user(77L, "target@example.com", "target");
		Friendship friendship = Friendship.request(currentUser, targetUser);
		when(userRepository.findByIdAndDeletedAtIsNull(42L)).thenReturn(Optional.of(currentUser));
		when(userRepository.findByIdAndDeletedAtIsNull(77L)).thenReturn(Optional.of(targetUser));
		when(friendshipRepository.findByUserPair(42L, 77L)).thenReturn(Optional.of(friendship));

		assertThatThrownBy(() -> service.acceptFriendRequest(principal(42L), 77L))
			.isInstanceOf(CannotAcceptOwnFriendRequestException.class);

		assertThat(friendship.getStatus()).isEqualTo(FriendshipStatus.pending);
	}

	@Test
	void acceptFriendRequestThrowsFriendshipNotFoundWhenAlreadyAccepted() {
		User currentUser = user(42L, "current@example.com", "current");
		User targetUser = user(77L, "target@example.com", "target");
		Friendship friendship = Friendship.request(targetUser, currentUser);
		friendship.accept();
		when(userRepository.findByIdAndDeletedAtIsNull(42L)).thenReturn(Optional.of(currentUser));
		when(userRepository.findByIdAndDeletedAtIsNull(77L)).thenReturn(Optional.of(targetUser));
		when(friendshipRepository.findByUserPair(42L, 77L)).thenReturn(Optional.of(friendship));

		assertThatThrownBy(() -> service.acceptFriendRequest(principal(42L), 77L))
			.isInstanceOf(FriendshipNotFoundException.class);
	}

	@Test
	void acceptFriendRequestRejectsBlockedFriendship() {
		User currentUser = user(42L, "current@example.com", "current");
		User targetUser = user(77L, "target@example.com", "target");
		Friendship friendship = Friendship.blocked(targetUser, currentUser, targetUser);
		when(userRepository.findByIdAndDeletedAtIsNull(42L)).thenReturn(Optional.of(currentUser));
		when(userRepository.findByIdAndDeletedAtIsNull(77L)).thenReturn(Optional.of(targetUser));
		when(friendshipRepository.findByUserPair(42L, 77L)).thenReturn(Optional.of(friendship));

		assertThatThrownBy(() -> service.acceptFriendRequest(principal(42L), 77L))
			.isInstanceOf(BlockedFriendshipException.class);
	}

	@Test
	void deleteFriendshipDeletesPendingRelation() {
		User currentUser = user(42L, "current@example.com", "current");
		User targetUser = user(77L, "target@example.com", "target");
		Friendship friendship = Friendship.request(targetUser, currentUser);
		when(userRepository.findByIdAndDeletedAtIsNull(42L)).thenReturn(Optional.of(currentUser));
		when(userRepository.findByIdAndDeletedAtIsNull(77L)).thenReturn(Optional.of(targetUser));
		when(friendshipRepository.findByUserPair(42L, 77L)).thenReturn(Optional.of(friendship));

		service.deleteFriendship(principal(42L), 77L);

		verify(friendshipRepository).delete(friendship);
	}

	@Test
	void deleteFriendshipDeletesAcceptedRelation() {
		User currentUser = user(42L, "current@example.com", "current");
		User targetUser = user(77L, "target@example.com", "target");
		Friendship friendship = Friendship.request(targetUser, currentUser);
		friendship.accept();
		when(userRepository.findByIdAndDeletedAtIsNull(42L)).thenReturn(Optional.of(currentUser));
		when(userRepository.findByIdAndDeletedAtIsNull(77L)).thenReturn(Optional.of(targetUser));
		when(friendshipRepository.findByUserPair(42L, 77L)).thenReturn(Optional.of(friendship));

		service.deleteFriendship(principal(42L), 77L);

		verify(friendshipRepository).delete(friendship);
	}

	@Test
	void deleteFriendshipThrowsUserNotFoundWhenCurrentUserIsMissingOrDeleted() {
		when(userRepository.findByIdAndDeletedAtIsNull(42L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.deleteFriendship(principal(42L), 77L))
			.isInstanceOf(UserNotFoundException.class);

		verify(friendshipRepository, never()).findByUserPair(any(), any());
		verify(friendshipRepository, never()).delete(any(Friendship.class));
	}

	@Test
	void deleteFriendshipThrowsUserNotFoundWhenTargetUserIsMissingOrDeleted() {
		User currentUser = user(42L, "current@example.com", "current");
		when(userRepository.findByIdAndDeletedAtIsNull(42L)).thenReturn(Optional.of(currentUser));
		when(userRepository.findByIdAndDeletedAtIsNull(77L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.deleteFriendship(principal(42L), 77L))
			.isInstanceOf(UserNotFoundException.class);

		verify(friendshipRepository, never()).findByUserPair(any(), any());
		verify(friendshipRepository, never()).delete(any(Friendship.class));
	}

	@Test
	void deleteFriendshipRejectsSelfAction() {
		User currentUser = user(42L, "current@example.com", "current");
		when(userRepository.findByIdAndDeletedAtIsNull(42L)).thenReturn(Optional.of(currentUser));

		assertThatThrownBy(() -> service.deleteFriendship(principal(42L), 42L))
			.isInstanceOf(SelfFriendActionException.class);

		verify(friendshipRepository, never()).findByUserPair(any(), any());
		verify(friendshipRepository, never()).delete(any(Friendship.class));
	}

	@Test
	void deleteFriendshipThrowsFriendshipNotFoundWhenRelationDoesNotExist() {
		User currentUser = user(42L, "current@example.com", "current");
		User targetUser = user(77L, "target@example.com", "target");
		when(userRepository.findByIdAndDeletedAtIsNull(42L)).thenReturn(Optional.of(currentUser));
		when(userRepository.findByIdAndDeletedAtIsNull(77L)).thenReturn(Optional.of(targetUser));
		when(friendshipRepository.findByUserPair(42L, 77L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.deleteFriendship(principal(42L), 77L))
			.isInstanceOf(FriendshipNotFoundException.class);

		verify(friendshipRepository, never()).delete(any(Friendship.class));
	}

	@Test
	void deleteFriendshipRejectsBlockedFriendship() {
		User currentUser = user(42L, "current@example.com", "current");
		User targetUser = user(77L, "target@example.com", "target");
		Friendship friendship = Friendship.blocked(currentUser, targetUser, currentUser);
		when(userRepository.findByIdAndDeletedAtIsNull(42L)).thenReturn(Optional.of(currentUser));
		when(userRepository.findByIdAndDeletedAtIsNull(77L)).thenReturn(Optional.of(targetUser));
		when(friendshipRepository.findByUserPair(42L, 77L)).thenReturn(Optional.of(friendship));

		assertThatThrownBy(() -> service.deleteFriendship(principal(42L), 77L))
			.isInstanceOf(BlockedFriendshipException.class);

		verify(friendshipRepository, never()).delete(any(Friendship.class));
	}

	@Test
	void blockUserCreatesBlockedFriendshipWhenRelationDoesNotExist() {
		User currentUser = user(42L, "current@example.com", "current");
		User targetUser = user(77L, "target@example.com", "target");
		when(userRepository.findByIdAndDeletedAtIsNull(42L)).thenReturn(Optional.of(currentUser));
		when(userRepository.findByIdAndDeletedAtIsNull(77L)).thenReturn(Optional.of(targetUser));
		when(friendshipRepository.findByUserPair(42L, 77L)).thenReturn(Optional.empty());
		when(friendshipRepository.save(any(Friendship.class))).thenAnswer(invocation -> {
			Friendship friendship = invocation.getArgument(0);
			assertThat(friendship.getRequester()).isEqualTo(currentUser);
			assertThat(friendship.getAddressee()).isEqualTo(targetUser);
			assertThat(friendship.getStatus()).isEqualTo(FriendshipStatus.blocked);
			assertThat(friendship.getBlockedBy()).isEqualTo(currentUser);
			return friendship;
		});

		service.blockUser(principal(42L), 77L);

		verify(friendshipRepository).save(any(Friendship.class));
	}

	@Test
	void blockUserUpdatesExistingPendingFriendship() {
		User currentUser = user(42L, "current@example.com", "current");
		User targetUser = user(77L, "target@example.com", "target");
		Friendship friendship = Friendship.request(targetUser, currentUser);
		when(userRepository.findByIdAndDeletedAtIsNull(42L)).thenReturn(Optional.of(currentUser));
		when(userRepository.findByIdAndDeletedAtIsNull(77L)).thenReturn(Optional.of(targetUser));
		when(friendshipRepository.findByUserPair(42L, 77L)).thenReturn(Optional.of(friendship));

		service.blockUser(principal(42L), 77L);

		assertThat(friendship.getStatus()).isEqualTo(FriendshipStatus.blocked);
		assertThat(friendship.getBlockedBy()).isEqualTo(currentUser);
		verify(friendshipRepository, never()).save(any(Friendship.class));
	}

	@Test
	void blockUserUpdatesExistingAcceptedFriendship() {
		User currentUser = user(42L, "current@example.com", "current");
		User targetUser = user(77L, "target@example.com", "target");
		Friendship friendship = Friendship.request(targetUser, currentUser);
		friendship.accept();
		when(userRepository.findByIdAndDeletedAtIsNull(42L)).thenReturn(Optional.of(currentUser));
		when(userRepository.findByIdAndDeletedAtIsNull(77L)).thenReturn(Optional.of(targetUser));
		when(friendshipRepository.findByUserPair(42L, 77L)).thenReturn(Optional.of(friendship));

		service.blockUser(principal(42L), 77L);

		assertThat(friendship.getStatus()).isEqualTo(FriendshipStatus.blocked);
		assertThat(friendship.getBlockedBy()).isEqualTo(currentUser);
		verify(friendshipRepository, never()).save(any(Friendship.class));
	}

	@Test
	void blockUserUpdatesExistingBlockedFriendship() {
		User currentUser = user(42L, "current@example.com", "current");
		User targetUser = user(77L, "target@example.com", "target");
		Friendship friendship = Friendship.blocked(targetUser, currentUser, targetUser);
		when(userRepository.findByIdAndDeletedAtIsNull(42L)).thenReturn(Optional.of(currentUser));
		when(userRepository.findByIdAndDeletedAtIsNull(77L)).thenReturn(Optional.of(targetUser));
		when(friendshipRepository.findByUserPair(42L, 77L)).thenReturn(Optional.of(friendship));

		service.blockUser(principal(42L), 77L);

		assertThat(friendship.getStatus()).isEqualTo(FriendshipStatus.blocked);
		assertThat(friendship.getBlockedBy()).isEqualTo(currentUser);
		verify(friendshipRepository, never()).save(any(Friendship.class));
	}

	@Test
	void blockUserThrowsUserNotFoundWhenCurrentUserIsMissingOrDeleted() {
		when(userRepository.findByIdAndDeletedAtIsNull(42L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.blockUser(principal(42L), 77L))
			.isInstanceOf(UserNotFoundException.class);

		verify(friendshipRepository, never()).findByUserPair(any(), any());
		verify(friendshipRepository, never()).save(any(Friendship.class));
	}

	@Test
	void blockUserThrowsUserNotFoundWhenTargetUserIsMissingOrDeleted() {
		User currentUser = user(42L, "current@example.com", "current");
		when(userRepository.findByIdAndDeletedAtIsNull(42L)).thenReturn(Optional.of(currentUser));
		when(userRepository.findByIdAndDeletedAtIsNull(77L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.blockUser(principal(42L), 77L))
			.isInstanceOf(UserNotFoundException.class);

		verify(friendshipRepository, never()).findByUserPair(any(), any());
		verify(friendshipRepository, never()).save(any(Friendship.class));
	}

	@Test
	void blockUserRejectsSelfAction() {
		User currentUser = user(42L, "current@example.com", "current");
		when(userRepository.findByIdAndDeletedAtIsNull(42L)).thenReturn(Optional.of(currentUser));

		assertThatThrownBy(() -> service.blockUser(principal(42L), 42L))
			.isInstanceOf(SelfFriendActionException.class);

		verify(friendshipRepository, never()).findByUserPair(any(), any());
		verify(friendshipRepository, never()).save(any(Friendship.class));
	}

	@Test
	void blockUserUpdatesRefetchedFriendshipWhenInsertHitsFriendPairConstraintRace() {
		User currentUser = user(42L, "current@example.com", "current");
		User targetUser = user(77L, "target@example.com", "target");
		Friendship friendship = Friendship.request(targetUser, currentUser);
		when(userRepository.findByIdAndDeletedAtIsNull(42L)).thenReturn(Optional.of(currentUser));
		when(userRepository.findByIdAndDeletedAtIsNull(77L)).thenReturn(Optional.of(targetUser));
		when(friendshipRepository.findByUserPair(42L, 77L))
			.thenReturn(Optional.empty())
			.thenReturn(Optional.of(friendship));
		when(friendshipRepository.save(any(Friendship.class)))
			.thenThrow(new DataIntegrityViolationException("uidx_friend_pair"));

		service.blockUser(principal(42L), 77L);

		assertThat(friendship.getStatus()).isEqualTo(FriendshipStatus.blocked);
		assertThat(friendship.getBlockedBy()).isEqualTo(currentUser);
		verify(friendshipRepository).save(any(Friendship.class));
	}

	@Test
	void blockUserRethrowsInsertRaceExceptionWhenRefetchStillFindsNoFriendship() {
		User currentUser = user(42L, "current@example.com", "current");
		User targetUser = user(77L, "target@example.com", "target");
		DataIntegrityViolationException exception = new DataIntegrityViolationException("uidx_friend_pair");
		when(userRepository.findByIdAndDeletedAtIsNull(42L)).thenReturn(Optional.of(currentUser));
		when(userRepository.findByIdAndDeletedAtIsNull(77L)).thenReturn(Optional.of(targetUser));
		when(friendshipRepository.findByUserPair(42L, 77L))
			.thenReturn(Optional.empty())
			.thenReturn(Optional.empty());
		when(friendshipRepository.save(any(Friendship.class))).thenThrow(exception);

		assertThatThrownBy(() -> service.blockUser(principal(42L), 77L))
			.isSameAs(exception);
	}

	@Test
	void blockUserRethrowsOtherDataIntegrityViolation() {
		User currentUser = user(42L, "current@example.com", "current");
		User targetUser = user(77L, "target@example.com", "target");
		DataIntegrityViolationException exception = new DataIntegrityViolationException("other_constraint");
		when(userRepository.findByIdAndDeletedAtIsNull(42L)).thenReturn(Optional.of(currentUser));
		when(userRepository.findByIdAndDeletedAtIsNull(77L)).thenReturn(Optional.of(targetUser));
		when(friendshipRepository.findByUserPair(42L, 77L)).thenReturn(Optional.empty());
		when(friendshipRepository.save(any(Friendship.class))).thenThrow(exception);

		assertThatThrownBy(() -> service.blockUser(principal(42L), 77L))
			.isSameAs(exception);
	}

	@Test
	void unblockUserDeletesBlockedFriendshipWhenCurrentUserBlockedTarget() {
		User currentUser = user(42L, "current@example.com", "current");
		User targetUser = user(77L, "target@example.com", "target");
		Friendship friendship = Friendship.blocked(currentUser, targetUser, currentUser);
		when(userRepository.findByIdAndDeletedAtIsNull(42L)).thenReturn(Optional.of(currentUser));
		when(userRepository.findByIdAndDeletedAtIsNull(77L)).thenReturn(Optional.of(targetUser));
		when(friendshipRepository.findByUserPair(42L, 77L)).thenReturn(Optional.of(friendship));

		service.unblockUser(principal(42L), 77L);

		verify(friendshipRepository).delete(friendship);
	}

	@Test
	void unblockUserThrowsUserNotFoundWhenCurrentUserIsMissingOrDeleted() {
		when(userRepository.findByIdAndDeletedAtIsNull(42L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.unblockUser(principal(42L), 77L))
			.isInstanceOf(UserNotFoundException.class);

		verify(friendshipRepository, never()).findByUserPair(any(), any());
		verify(friendshipRepository, never()).delete(any(Friendship.class));
	}

	@Test
	void unblockUserThrowsUserNotFoundWhenTargetUserIsMissingOrDeleted() {
		User currentUser = user(42L, "current@example.com", "current");
		when(userRepository.findByIdAndDeletedAtIsNull(42L)).thenReturn(Optional.of(currentUser));
		when(userRepository.findByIdAndDeletedAtIsNull(77L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.unblockUser(principal(42L), 77L))
			.isInstanceOf(UserNotFoundException.class);

		verify(friendshipRepository, never()).findByUserPair(any(), any());
		verify(friendshipRepository, never()).delete(any(Friendship.class));
	}

	@Test
	void unblockUserRejectsSelfAction() {
		User currentUser = user(42L, "current@example.com", "current");
		when(userRepository.findByIdAndDeletedAtIsNull(42L)).thenReturn(Optional.of(currentUser));

		assertThatThrownBy(() -> service.unblockUser(principal(42L), 42L))
			.isInstanceOf(SelfFriendActionException.class);

		verify(friendshipRepository, never()).findByUserPair(any(), any());
		verify(friendshipRepository, never()).delete(any(Friendship.class));
	}

	@Test
	void unblockUserThrowsFriendshipNotFoundWhenRelationDoesNotExist() {
		User currentUser = user(42L, "current@example.com", "current");
		User targetUser = user(77L, "target@example.com", "target");
		when(userRepository.findByIdAndDeletedAtIsNull(42L)).thenReturn(Optional.of(currentUser));
		when(userRepository.findByIdAndDeletedAtIsNull(77L)).thenReturn(Optional.of(targetUser));
		when(friendshipRepository.findByUserPair(42L, 77L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.unblockUser(principal(42L), 77L))
			.isInstanceOf(FriendshipNotFoundException.class);

		verify(friendshipRepository, never()).delete(any(Friendship.class));
	}

	@Test
	void unblockUserThrowsFriendshipNotFoundWhenRelationIsPending() {
		User currentUser = user(42L, "current@example.com", "current");
		User targetUser = user(77L, "target@example.com", "target");
		Friendship friendship = Friendship.request(currentUser, targetUser);
		when(userRepository.findByIdAndDeletedAtIsNull(42L)).thenReturn(Optional.of(currentUser));
		when(userRepository.findByIdAndDeletedAtIsNull(77L)).thenReturn(Optional.of(targetUser));
		when(friendshipRepository.findByUserPair(42L, 77L)).thenReturn(Optional.of(friendship));

		assertThatThrownBy(() -> service.unblockUser(principal(42L), 77L))
			.isInstanceOf(FriendshipNotFoundException.class);

		verify(friendshipRepository, never()).delete(any(Friendship.class));
	}

	@Test
	void unblockUserThrowsFriendshipNotFoundWhenRelationIsAccepted() {
		User currentUser = user(42L, "current@example.com", "current");
		User targetUser = user(77L, "target@example.com", "target");
		Friendship friendship = Friendship.request(currentUser, targetUser);
		friendship.accept();
		when(userRepository.findByIdAndDeletedAtIsNull(42L)).thenReturn(Optional.of(currentUser));
		when(userRepository.findByIdAndDeletedAtIsNull(77L)).thenReturn(Optional.of(targetUser));
		when(friendshipRepository.findByUserPair(42L, 77L)).thenReturn(Optional.of(friendship));

		assertThatThrownBy(() -> service.unblockUser(principal(42L), 77L))
			.isInstanceOf(FriendshipNotFoundException.class);

		verify(friendshipRepository, never()).delete(any(Friendship.class));
	}

	@Test
	void unblockUserThrowsBlockedFriendshipWhenTargetBlockedCurrentUser() {
		User currentUser = user(42L, "current@example.com", "current");
		User targetUser = user(77L, "target@example.com", "target");
		Friendship friendship = Friendship.blocked(targetUser, currentUser, targetUser);
		when(userRepository.findByIdAndDeletedAtIsNull(42L)).thenReturn(Optional.of(currentUser));
		when(userRepository.findByIdAndDeletedAtIsNull(77L)).thenReturn(Optional.of(targetUser));
		when(friendshipRepository.findByUserPair(42L, 77L)).thenReturn(Optional.of(friendship));

		assertThatThrownBy(() -> service.unblockUser(principal(42L), 77L))
			.isInstanceOf(BlockedFriendshipException.class);

		verify(friendshipRepository, never()).delete(any(Friendship.class));
	}

	@Test
	void listFriendsMapsAcceptedOtherUsersWhenCurrentUserIsActive() {
		User currentUser = user(42L, "current@example.com", "current");
		User firstFriend = user(77L, "first@example.com", "first");
		User secondFriend = user(88L, "second@example.com", "second");
		OffsetDateTime activeAt = OffsetDateTime.now().plusMinutes(1);
		setField(firstFriend, "lastActiveAt", activeAt);
		Friendship first = acceptedFriendship(currentUser, firstFriend);
		Friendship second = acceptedFriendship(secondFriend, currentUser);
		when(userRepository.findByIdAndDeletedAtIsNull(42L)).thenReturn(Optional.of(currentUser));
		when(friendshipRepository.findAcceptedByUserId(42L)).thenReturn(List.of(first, second));

		List<FriendResponse> responses = service.listFriends(principal(42L));

		assertThat(responses).hasSize(2);
		assertThat(responses.get(0).userId()).isEqualTo(77L);
		assertThat(responses.get(0).nickname()).isEqualTo("first");
		assertThat(responses.get(0).lastActiveAt()).isEqualTo(activeAt);
		assertThat(responses.get(0).active()).isTrue();
		assertThat(responses.get(1).userId()).isEqualTo(88L);
		assertThat(responses.get(1).nickname()).isEqualTo("second");
	}

	@Test
	void listFriendsThrowsUserNotFoundWhenCurrentUserIsMissingOrDeleted() {
		when(userRepository.findByIdAndDeletedAtIsNull(42L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.listFriends(principal(42L)))
			.isInstanceOf(UserNotFoundException.class);

		verify(friendshipRepository, never()).findAcceptedByUserId(any());
	}

	@Test
	void listFriendRequestsReceivedMapsPendingRequesters() {
		User currentUser = user(42L, "current@example.com", "current");
		User requester = user(77L, "requester@example.com", "requester");
		OffsetDateTime requestedAt = OffsetDateTime.parse("2026-07-08T10:15:30+09:00");
		Friendship friendship = Friendship.request(requester, currentUser);
		setField(friendship, "createdAt", requestedAt);
		when(userRepository.findByIdAndDeletedAtIsNull(42L)).thenReturn(Optional.of(currentUser));
		when(friendshipRepository.findPendingReceivedByUserId(42L)).thenReturn(List.of(friendship));

		List<FriendRequestResponse> responses = service.listFriendRequests(principal(42L), "received");

		assertThat(responses).hasSize(1);
		assertThat(responses.get(0).userId()).isEqualTo(77L);
		assertThat(responses.get(0).nickname()).isEqualTo("requester");
		assertThat(responses.get(0).requestedAt()).isEqualTo(requestedAt);
		verify(friendshipRepository, never()).findPendingSentByUserId(any());
	}

	@Test
	void listFriendRequestsSentMapsPendingAddressees() {
		User currentUser = user(42L, "current@example.com", "current");
		User addressee = user(77L, "addressee@example.com", "addressee");
		OffsetDateTime requestedAt = OffsetDateTime.parse("2026-07-08T10:15:30+09:00");
		Friendship friendship = Friendship.request(currentUser, addressee);
		setField(friendship, "createdAt", requestedAt);
		when(userRepository.findByIdAndDeletedAtIsNull(42L)).thenReturn(Optional.of(currentUser));
		when(friendshipRepository.findPendingSentByUserId(42L)).thenReturn(List.of(friendship));

		List<FriendRequestResponse> responses = service.listFriendRequests(principal(42L), "sent");

		assertThat(responses).hasSize(1);
		assertThat(responses.get(0).userId()).isEqualTo(77L);
		assertThat(responses.get(0).nickname()).isEqualTo("addressee");
		assertThat(responses.get(0).requestedAt()).isEqualTo(requestedAt);
		verify(friendshipRepository, never()).findPendingReceivedByUserId(any());
	}

	@Test
	void listFriendRequestsRejectsInvalidDirection() {
		User currentUser = user(42L, "current@example.com", "current");
		when(userRepository.findByIdAndDeletedAtIsNull(42L)).thenReturn(Optional.of(currentUser));

		assertThatThrownBy(() -> service.listFriendRequests(principal(42L), "all"))
			.isInstanceOf(IllegalArgumentException.class);

		verify(friendshipRepository, never()).findPendingReceivedByUserId(any());
		verify(friendshipRepository, never()).findPendingSentByUserId(any());
	}

	@Test
	void listFriendRequestsRejectsNullDirection() {
		User currentUser = user(42L, "current@example.com", "current");
		when(userRepository.findByIdAndDeletedAtIsNull(42L)).thenReturn(Optional.of(currentUser));

		assertThatThrownBy(() -> service.listFriendRequests(principal(42L), null))
			.isInstanceOf(IllegalArgumentException.class);

		verify(friendshipRepository, never()).findPendingReceivedByUserId(any());
		verify(friendshipRepository, never()).findPendingSentByUserId(any());
	}

	@Test
	void listFriendRequestsThrowsUserNotFoundWhenCurrentUserIsMissingOrDeleted() {
		when(userRepository.findByIdAndDeletedAtIsNull(42L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.listFriendRequests(principal(42L), "received"))
			.isInstanceOf(UserNotFoundException.class);

		verify(friendshipRepository, never()).findPendingReceivedByUserId(any());
		verify(friendshipRepository, never()).findPendingSentByUserId(any());
	}

	@Test
	void listBlocksMapsUsersBlockedByCurrentUser() {
		User currentUser = user(42L, "current@example.com", "current");
		User blockedUser = user(77L, "blocked@example.com", "blocked");
		OffsetDateTime blockedAt = OffsetDateTime.parse("2026-07-08T10:15:30+09:00");
		Friendship friendship = Friendship.blocked(currentUser, blockedUser, currentUser);
		setField(friendship, "updatedAt", blockedAt);
		when(userRepository.findByIdAndDeletedAtIsNull(42L)).thenReturn(Optional.of(currentUser));
		when(friendshipRepository.findBlockedByUserId(42L)).thenReturn(List.of(friendship));

		List<BlockedUserResponse> responses = service.listBlocks(principal(42L));

		assertThat(responses).hasSize(1);
		assertThat(responses.get(0).userId()).isEqualTo(77L);
		assertThat(responses.get(0).nickname()).isEqualTo("blocked");
		assertThat(responses.get(0).blockedAt()).isEqualTo(blockedAt);
	}

	@Test
	void listBlocksThrowsUserNotFoundWhenCurrentUserIsMissingOrDeleted() {
		when(userRepository.findByIdAndDeletedAtIsNull(42L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.listBlocks(principal(42L)))
			.isInstanceOf(UserNotFoundException.class);

		verify(friendshipRepository, never()).findBlockedByUserId(any());
	}

	@Test
	void listBlockedUserIdsWrapsSymmetricRepositoryIds() {
		User currentUser = user(42L, "current@example.com", "current");
		when(userRepository.findByIdAndDeletedAtIsNull(42L)).thenReturn(Optional.of(currentUser));
		when(friendshipRepository.findBlockedUserIdsByUserId(42L)).thenReturn(List.of(77L, 88L));

		BlockedUserIdsResponse response = service.listBlockedUserIds(principal(42L));

		assertThat(response.userIds()).containsExactly(77L, 88L);
	}

	@Test
	void listBlockedUserIdsThrowsUserNotFoundWhenCurrentUserIsMissingOrDeleted() {
		when(userRepository.findByIdAndDeletedAtIsNull(42L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.listBlockedUserIds(principal(42L)))
			.isInstanceOf(UserNotFoundException.class);

		verify(friendshipRepository, never()).findBlockedUserIdsByUserId(any());
	}

	@Test
	void areFriendsDelegatesToRepositoryExistsAcceptedByUserPair() {
		when(friendshipRepository.existsAcceptedByUserPair(42L, 77L)).thenReturn(true);

		assertThat(service.areFriends(42L, 77L)).isTrue();
	}

	@Test
	void hasBlockBetweenDelegatesToRepositoryExistsBlockedByUserPair() {
		when(friendshipRepository.existsBlockedByUserPair(42L, 77L)).thenReturn(true);

		assertThat(service.hasBlockBetween(42L, 77L)).isTrue();
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
		setField(user, "id", id);
	}

	private Friendship acceptedFriendship(User requester, User addressee) {
		Friendship friendship = Friendship.request(requester, addressee);
		friendship.accept();
		return friendship;
	}

	private void setField(Object target, String fieldName, Object value) {
		try {
			java.lang.reflect.Field field = target.getClass().getDeclaredField(fieldName);
			field.setAccessible(true);
			field.set(target, value);
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException(exception);
		}
	}
}
