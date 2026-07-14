package shinhan.fibri.ieum.common.friend.repository;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import shinhan.fibri.ieum.common.auth.domain.GenderType;
import shinhan.fibri.ieum.common.auth.domain.User;
import shinhan.fibri.ieum.common.friend.domain.Friendship;

@DataJpaTest
class FriendshipRepositoryTest {

	@Autowired
	private FriendshipRepository friendshipRepository;

	@Autowired
	private EntityManager entityManager;

	@Test
	void findByUserPairFindsFriendshipRegardlessOfDirection() {
		User requester = persist(user("requester@example.com", "requester"));
		User addressee = persist(user("addressee@example.com", "addressee"));
		Friendship friendship = friendshipRepository.save(Friendship.request(requester, addressee));

		assertThat(friendshipRepository.findByUserPair(requester.getId(), addressee.getId()))
			.contains(friendship);
		assertThat(friendshipRepository.findByUserPair(addressee.getId(), requester.getId()))
			.contains(friendship);
	}

	@Test
	void findAcceptedByUserIdReturnsOnlyAcceptedFriendshipsForUser() {
		User me = persist(user("me@example.com", "me"));
		User accepted = persist(user("accepted@example.com", "accepted"));
		User pending = persist(user("pending@example.com", "pending"));
		Friendship acceptedFriendship = Friendship.request(me, accepted);
		acceptedFriendship.accept();
		friendshipRepository.save(acceptedFriendship);
		friendshipRepository.save(Friendship.request(pending, me));

		assertThat(friendshipRepository.findAcceptedByUserId(me.getId()))
			.containsExactly(acceptedFriendship);
	}

	@Test
	void findPendingReceivedAndSentByUserIdSeparateRequestDirection() {
		User me = persist(user("direction-me@example.com", "direction-me"));
		User receivedRequester = persist(user("received-requester@example.com", "received-requester"));
		User sentAddressee = persist(user("sent-addressee@example.com", "sent-addressee"));
		User unrelated = persist(user("unrelated-pending@example.com", "unrelated-pending"));
		Friendship received = friendshipRepository.save(Friendship.request(receivedRequester, me));
		Friendship sent = friendshipRepository.save(Friendship.request(me, sentAddressee));
		friendshipRepository.save(Friendship.request(receivedRequester, unrelated));

		assertThat(friendshipRepository.findPendingReceivedByUserId(me.getId()))
			.containsExactly(received);
		assertThat(friendshipRepository.findPendingSentByUserId(me.getId()))
			.containsExactly(sent);
	}

	@Test
	void findBlockedByUserIdReturnsOnlyUsersBlockedByMe() {
		User me = persist(user("block-me@example.com", "block-me"));
		User blockedByMe = persist(user("blocked-by-me@example.com", "blocked-by-me"));
		User blockedMe = persist(user("blocked-me@example.com", "blocked-me"));
		Friendship myBlock = friendshipRepository.save(Friendship.blocked(me, blockedByMe, me));
		friendshipRepository.save(Friendship.blocked(blockedMe, me, blockedMe));

		assertThat(friendshipRepository.findBlockedByUserId(me.getId()))
			.containsExactly(myBlock);
	}

	@Test
	void findBlockedUserIdsByUserIdReturnsSymmetricBlockedUsers() {
		User me = persist(user("symmetric-me@example.com", "symmetric-me"));
		User blockedByMe = persist(user("symmetric-blocked-by-me@example.com", "symmetric-blocked-by-me"));
		User blockedMe = persist(user("symmetric-blocked-me@example.com", "symmetric-blocked-me"));
		User pending = persist(user("symmetric-pending@example.com", "symmetric-pending"));
		friendshipRepository.save(Friendship.blocked(me, blockedByMe, me));
		friendshipRepository.save(Friendship.blocked(blockedMe, me, blockedMe));
		friendshipRepository.save(Friendship.request(me, pending));

		assertThat(friendshipRepository.findBlockedUserIdsByUserId(me.getId()))
			.containsExactlyInAnyOrder(blockedByMe.getId(), blockedMe.getId());
	}

	@Test
	void existsAcceptedByUserPairReturnsTrueRegardlessOfDirection() {
		User first = persist(user("accepted-first@example.com", "accepted-first"));
		User second = persist(user("accepted-second@example.com", "accepted-second"));
		User pending = persist(user("accepted-pending@example.com", "accepted-pending"));
		Friendship accepted = Friendship.request(first, second);
		accepted.accept();
		friendshipRepository.save(accepted);
		friendshipRepository.save(Friendship.request(first, pending));

		assertThat(friendshipRepository.existsAcceptedByUserPair(first.getId(), second.getId()))
			.isTrue();
		assertThat(friendshipRepository.existsAcceptedByUserPair(second.getId(), first.getId()))
			.isTrue();
		assertThat(friendshipRepository.existsAcceptedByUserPair(first.getId(), pending.getId()))
			.isFalse();
	}

	@Test
	void existsBlockedByUserPairReturnsTrueRegardlessOfDirection() {
		User first = persist(user("blocked-first@example.com", "blocked-first"));
		User second = persist(user("blocked-second@example.com", "blocked-second"));
		User acceptedUser = persist(user("blocked-accepted@example.com", "blocked-accepted"));
		Friendship accepted = Friendship.request(first, acceptedUser);
		accepted.accept();
		friendshipRepository.save(Friendship.blocked(first, second, first));
		friendshipRepository.save(accepted);

		assertThat(friendshipRepository.existsBlockedByUserPair(first.getId(), second.getId()))
			.isTrue();
		assertThat(friendshipRepository.existsBlockedByUserPair(second.getId(), first.getId()))
			.isTrue();
		assertThat(friendshipRepository.existsBlockedByUserPair(first.getId(), acceptedUser.getId()))
			.isFalse();
	}

	@Test
	void findAcceptedByUserIdExcludesSoftDeletedFriend() {
		User me = persist(user("me-active@example.com", "me-active"));
		User activeFriend = persist(user("active-friend@example.com", "active-friend"));
		User deletedFriend = persist(user("deleted-friend@example.com", "deleted-friend"));
		deletedFriend.markDeleted(OffsetDateTime.now());
		Friendship acceptedActive = Friendship.request(me, activeFriend);
		acceptedActive.accept();
		Friendship acceptedDeleted = Friendship.request(me, deletedFriend);
		acceptedDeleted.accept();
		friendshipRepository.save(acceptedActive);
		friendshipRepository.save(acceptedDeleted);

		assertThat(friendshipRepository.findAcceptedByUserId(me.getId()))
			.extracting(friendship -> friendship.otherUser(me.getId()).getId())
			.containsExactly(activeFriend.getId());
	}

	private User user(String email, String nickname) {
		return User.createEmailUser(
			email,
			"hash",
			nickname,
			LocalDate.of(1995, 1, 1),
			GenderType.female,
			"KR"
		);
	}

	private User persist(User user) {
		entityManager.persist(user);
		return user;
	}

	@SpringBootApplication(scanBasePackages = "shinhan.fibri.ieum.common")
	@EntityScan(basePackageClasses = {User.class, Friendship.class})
	static class TestApplication {
	}
}
