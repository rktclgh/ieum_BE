package shinhan.fibri.ieum.common.friend.repository;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import java.time.LocalDate;
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
