package shinhan.fibri.ieum.main.friend.service;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import shinhan.fibri.ieum.common.auth.domain.User;
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
import shinhan.fibri.ieum.main.notification.presence.UserPresenceQuery;
import shinhan.fibri.ieum.main.user.exception.UserNotFoundException;

@Service
@RequiredArgsConstructor
public class FriendService {

	private final UserRepository userRepository;
	private final FriendshipRepository friendshipRepository;
	private final FriendRequestNotifier friendRequestNotifier;
	private final UserPresenceQuery userPresenceQuery;
	private final PlatformTransactionManager transactionManager;

	@Transactional(readOnly = true)
	public List<FriendResponse> listFriends(AuthenticatedUser principal) {
		User currentUser = findActiveUser(principal.userId());
		return friendshipRepository.findAcceptedByUserId(currentUser.getId()).stream()
			.map(friendship -> friendship.otherUser(currentUser.getId()))
			.map(user -> FriendResponse.from(user, userPresenceQuery.isOnline(user.getId())))
			.toList();
	}

	@Transactional(readOnly = true)
	public List<FriendRequestResponse> listFriendRequests(AuthenticatedUser principal, String direction) {
		User currentUser = findActiveUser(principal.userId());
		if (direction == null) {
			throw invalidFriendRequestDirection();
		}
		return switch (direction) {
			case "received" -> friendshipRepository.findPendingReceivedByUserId(currentUser.getId()).stream()
				.map(friendship -> FriendRequestResponse.from(friendship.getRequester(), friendship.getCreatedAt()))
				.toList();
			case "sent" -> friendshipRepository.findPendingSentByUserId(currentUser.getId()).stream()
				.map(friendship -> FriendRequestResponse.from(friendship.getAddressee(), friendship.getCreatedAt()))
				.toList();
			default -> throw invalidFriendRequestDirection();
		};
	}

	@Transactional(readOnly = true)
	public List<BlockedUserResponse> listBlocks(AuthenticatedUser principal) {
		User currentUser = findActiveUser(principal.userId());
		return friendshipRepository.findBlockedByUserId(currentUser.getId()).stream()
			.map(friendship -> BlockedUserResponse.from(friendship.otherUser(currentUser.getId()), friendship.getUpdatedAt()))
			.toList();
	}

	@Transactional(readOnly = true)
	public BlockedUserIdsResponse listBlockedUserIds(AuthenticatedUser principal) {
		User currentUser = findActiveUser(principal.userId());
		return BlockedUserIdsResponse.from(friendshipRepository.findBlockedUserIdsByUserId(currentUser.getId()));
	}

	@Transactional(readOnly = true)
	public boolean areFriends(Long firstUserId, Long secondUserId) {
		return friendshipRepository.existsAcceptedByUserPair(firstUserId, secondUserId);
	}

	@Transactional(readOnly = true)
	public boolean hasBlockBetween(Long firstUserId, Long secondUserId) {
		return friendshipRepository.existsBlockedByUserPair(firstUserId, secondUserId);
	}

	@Transactional(readOnly = true)
	public Set<Long> acceptedFriendIdsOf(Long userId) {
		return new HashSet<>(friendshipRepository.findAcceptedUserIdsByUserId(userId));
	}

	@Transactional(readOnly = true)
	public Set<Long> blockedUserIdsOf(Long userId) {
		return new HashSet<>(friendshipRepository.findBlockedUserIdsByUserId(userId));
	}

	@Transactional(timeout = 30)
	public void requestFriend(AuthenticatedUser principal, Long targetUserId) {
		User requester = findActiveUser(principal.userId());
		if (requester.getId().equals(targetUserId)) {
			throw new SelfFriendRequestException();
		}
		User addressee = findActiveUser(targetUserId);

		friendshipRepository.findByUserPair(requester.getId(), addressee.getId())
			.ifPresent(this::rejectExistingFriendship);

		try {
			friendshipRepository.saveAndFlush(Friendship.request(requester, addressee));
		} catch (DataIntegrityViolationException exception) {
			// 사전 조회와 INSERT 사이의 동시 요청 레이스 → uidx_friend_pair 충돌
			if (isFriendPairConstraintViolation(exception)) {
				throw new FriendRequestExistsException();
			}
			throw exception;
		}
		friendRequestNotifier.notifyRequested(requester.getId(), addressee.getId());
	}

	@Transactional
	public void acceptFriendRequest(AuthenticatedUser principal, Long targetUserId) {
		User currentUser = findActiveUser(principal.userId());
		if (currentUser.getId().equals(targetUserId)) {
			throw new SelfFriendActionException();
		}
		User targetUser = findActiveUser(targetUserId);

		Friendship friendship = friendshipRepository.findByUserPair(currentUser.getId(), targetUser.getId())
			.orElseThrow(FriendshipNotFoundException::new);

		if (friendship.getStatus() == FriendshipStatus.blocked) {
			throw new BlockedFriendshipException();
		}
		if (friendship.getStatus() != FriendshipStatus.pending) {
			throw new FriendshipNotFoundException();
		}
		if (friendship.getAddressee().getId().equals(currentUser.getId())) {
			friendship.accept();
			return;
		}
		if (friendship.getRequester().getId().equals(currentUser.getId())) {
			throw new CannotAcceptOwnFriendRequestException();
		}
		throw new FriendshipNotFoundException();
	}

	@Transactional
	public void deleteFriendship(AuthenticatedUser principal, Long targetUserId) {
		User currentUser = findActiveUser(principal.userId());
		if (currentUser.getId().equals(targetUserId)) {
			throw new SelfFriendActionException();
		}
		User targetUser = findActiveUser(targetUserId);

		Friendship friendship = friendshipRepository.findByUserPair(currentUser.getId(), targetUser.getId())
			.orElseThrow(FriendshipNotFoundException::new);

		if (friendship.getStatus() == FriendshipStatus.blocked) {
			throw new BlockedFriendshipException();
		}
		if (friendship.getStatus() != FriendshipStatus.pending && friendship.getStatus() != FriendshipStatus.accepted) {
			throw new FriendshipNotFoundException();
		}
		friendshipRepository.delete(friendship);
	}

	// 차단은 pair 1행 upsert다. INSERT 충돌은 Postgres가 트랜잭션을 abort시키므로 같은 트랜잭션 내
	// 복구가 불가능하다 → 트랜잭션 밖에서 새 트랜잭션으로 1회 재시도(재시도 시 커밋된 행을 재조회해 UPDATE).
	public void blockUser(AuthenticatedUser principal, Long targetUserId) {
		try {
			blockInNewTransaction(principal, targetUserId);
		} catch (DataIntegrityViolationException exception) {
			if (!isFriendPairConstraintViolation(exception)) {
				throw exception;
			}
			blockInNewTransaction(principal, targetUserId);
		}
	}

	@Transactional
	public void unblockUser(AuthenticatedUser principal, Long targetUserId) {
		User currentUser = findActiveUser(principal.userId());
		if (currentUser.getId().equals(targetUserId)) {
			throw new SelfFriendActionException();
		}
		User targetUser = findActiveUser(targetUserId);

		Friendship friendship = friendshipRepository.findByUserPair(currentUser.getId(), targetUser.getId())
			.orElseThrow(FriendshipNotFoundException::new);

		if (friendship.getStatus() != FriendshipStatus.blocked) {
			throw new FriendshipNotFoundException();
		}
		if (!friendship.getBlockedBy().getId().equals(currentUser.getId())) {
			throw new BlockedFriendshipException();
		}
		friendshipRepository.delete(friendship);
	}

	private void blockInNewTransaction(AuthenticatedUser principal, Long targetUserId) {
		TransactionTemplate template = new TransactionTemplate(transactionManager);
		template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		template.executeWithoutResult(status -> blockUserInTransaction(principal, targetUserId));
	}

	private void blockUserInTransaction(AuthenticatedUser principal, Long targetUserId) {
		User currentUser = findActiveUser(principal.userId());
		if (currentUser.getId().equals(targetUserId)) {
			throw new SelfFriendActionException();
		}
		User targetUser = findActiveUser(targetUserId);

		friendshipRepository.findByUserPair(currentUser.getId(), targetUser.getId())
			.ifPresentOrElse(
				friendship -> friendship.blockBy(currentUser),
				() -> friendshipRepository.save(Friendship.blocked(currentUser, targetUser, currentUser))
			);
	}

	private User findActiveUser(Long userId) {
		return userRepository.findByIdAndDeletedAtIsNull(userId)
			.orElseThrow(UserNotFoundException::new);
	}

	private boolean isFriendPairConstraintViolation(DataIntegrityViolationException exception) {
		String constraint = constraintName(exception);
		return constraint != null && constraint.toLowerCase(Locale.ROOT).contains("uidx_friend_pair");
	}

	private String constraintName(DataIntegrityViolationException exception) {
		if (exception.getCause() instanceof ConstraintViolationException constraintViolation) {
			return constraintViolation.getConstraintName();
		}
		return exception.getMessage();
	}

	private IllegalArgumentException invalidFriendRequestDirection() {
		return new IllegalArgumentException("direction must be received or sent");
	}

	private void rejectExistingFriendship(Friendship friendship) {
		FriendshipStatus status = friendship.getStatus();
		if (status == FriendshipStatus.pending) {
			throw new FriendRequestExistsException();
		}
		if (status == FriendshipStatus.accepted) {
			throw new AlreadyFriendsException();
		}
		if (status == FriendshipStatus.blocked) {
			throw new BlockedFriendshipException();
		}
	}

}
