package shinhan.fibri.ieum.main.friend.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import shinhan.fibri.ieum.common.auth.domain.GenderType;
import shinhan.fibri.ieum.common.auth.domain.User;

class FriendRequestResponseTest {

	@Test
	void fromMapsUserFieldsAndRequestedAt() {
		User user = user(77L, "requester");
		user.linkProfileImage(UUID.fromString("22222222-2222-2222-2222-222222222222"));
		OffsetDateTime requestedAt = OffsetDateTime.parse("2026-07-08T12:00:00+09:00");

		FriendRequestResponse response = FriendRequestResponse.from(user, requestedAt);

		assertThat(response.userId()).isEqualTo(77L);
		assertThat(response.nickname()).isEqualTo("requester");
		assertThat(response.profileImageUrl()).isEqualTo("/api/v1/files/22222222-2222-2222-2222-222222222222");
		assertThat(response.nationality()).isEqualTo("KR");
		assertThat(response.requestedAt()).isEqualTo(requestedAt);
	}

	@Test
	void fromReturnsNullNationalityWhenUserHasNone() {
		User user = user(77L, "requester");
		ReflectionTestUtils.setField(user, "nationality", null);

		FriendRequestResponse response = FriendRequestResponse.from(
			user,
			OffsetDateTime.parse("2026-07-08T12:00:00+09:00")
		);

		assertThat(response.nationality()).isNull();
	}

	@Test
	void fromReturnsNullProfileImageUrlWhenProfileFileIdDoesNotExist() {
		User user = user(77L, "requester");

		FriendRequestResponse response = FriendRequestResponse.from(
			user,
			OffsetDateTime.parse("2026-07-08T12:00:00+09:00")
		);

		assertThat(response.profileImageUrl()).isNull();
	}

	private User user(Long id, String nickname) {
		User user = User.createEmailUser(
			nickname + "@example.com",
			"encoded-password",
			nickname,
			LocalDate.of(1995, 5, 20),
			GenderType.female,
			"KR"
		);
		ReflectionTestUtils.setField(user, "id", id);
		return user;
	}
}
