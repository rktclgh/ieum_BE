package shinhan.fibri.ieum.common.friend.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import shinhan.fibri.ieum.common.auth.domain.GenderType;
import shinhan.fibri.ieum.common.auth.domain.User;

class FriendshipTest {

	@Test
	void requestCreatesPendingFriendship() {
		User requester = user("requester@example.com", "requester");
		User addressee = user("addressee@example.com", "addressee");

		Friendship friendship = Friendship.request(requester, addressee);

		assertThat(friendship.getRequester()).isEqualTo(requester);
		assertThat(friendship.getAddressee()).isEqualTo(addressee);
		assertThat(friendship.getStatus()).isEqualTo(FriendshipStatus.pending);
		assertThat(friendship.getBlockedBy()).isNull();
	}

	@Test
	void acceptChangesPendingFriendshipToAccepted() {
		User requester = user("requester@example.com", "requester");
		User addressee = user("addressee@example.com", "addressee");
		Friendship friendship = Friendship.request(requester, addressee);

		friendship.accept();

		assertThat(friendship.getStatus()).isEqualTo(FriendshipStatus.accepted);
		assertThat(friendship.getBlockedBy()).isNull();
	}

	@Test
	void blockStoresBlockOwner() {
		User requester = user("requester@example.com", "requester");
		User addressee = user("addressee@example.com", "addressee");
		Friendship friendship = Friendship.request(requester, addressee);

		friendship.blockBy(addressee);

		assertThat(friendship.getStatus()).isEqualTo(FriendshipStatus.blocked);
		assertThat(friendship.getBlockedBy()).isEqualTo(addressee);
	}

	@Test
	void requestRejectsSameUserInstance() {
		User requester = user("requester@example.com", "requester");

		assertThatThrownBy(() -> Friendship.request(requester, requester))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("friendship participants must be different");
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
}
