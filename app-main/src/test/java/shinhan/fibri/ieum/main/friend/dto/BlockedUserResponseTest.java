package shinhan.fibri.ieum.main.friend.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import shinhan.fibri.ieum.common.auth.domain.GenderType;
import shinhan.fibri.ieum.common.auth.domain.User;

class BlockedUserResponseTest {

	@Test
	void fromMapsUserFieldsAndBlockedAt() {
		User user = user(77L, "blocked");
		user.linkProfileImage(UUID.fromString("33333333-3333-3333-3333-333333333333"));
		OffsetDateTime blockedAt = OffsetDateTime.parse("2026-07-08T12:00:00+09:00");

		BlockedUserResponse response = BlockedUserResponse.from(user, blockedAt);

		assertThat(response.userId()).isEqualTo(77L);
		assertThat(response.nickname()).isEqualTo("blocked");
		assertThat(response.profileImageUrl()).isEqualTo("/api/v1/files/33333333-3333-3333-3333-333333333333");
		assertThat(response.blockedAt()).isEqualTo(blockedAt);
	}

	@Test
	void fromReturnsNullProfileImageUrlWhenProfileFileIdDoesNotExist() {
		User user = user(77L, "blocked");

		BlockedUserResponse response = BlockedUserResponse.from(
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
