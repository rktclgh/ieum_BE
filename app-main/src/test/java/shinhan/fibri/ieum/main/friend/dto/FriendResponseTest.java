package shinhan.fibri.ieum.main.friend.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import shinhan.fibri.ieum.common.auth.domain.GenderType;
import shinhan.fibri.ieum.common.auth.domain.User;

class FriendResponseTest {

	private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-07-08T12:00:00+09:00");

	@Test
	void fromMapsUserFieldsAndProfileImageUrl() {
		User user = user(77L, "friend");
		user.linkProfileImage(UUID.fromString("11111111-1111-1111-1111-111111111111"));
		ReflectionTestUtils.setField(user, "lastActiveAt", NOW.minusMinutes(4));

		FriendResponse response = FriendResponse.from(user, NOW);

		assertThat(response.userId()).isEqualTo(77L);
		assertThat(response.nickname()).isEqualTo("friend");
		assertThat(response.profileImageUrl()).isEqualTo("/api/v1/files/11111111-1111-1111-1111-111111111111");
		assertThat(response.nationality()).isEqualTo("KR");
		assertThat(response.lastActiveAt()).isEqualTo(NOW.minusMinutes(4));
		assertThat(response.active()).isTrue();
	}

	@Test
	void fromReturnsNullNationalityWhenUserHasNone() {
		User user = user(77L, "friend");
		ReflectionTestUtils.setField(user, "nationality", null);

		FriendResponse response = FriendResponse.from(user, NOW);

		assertThat(response.nationality()).isNull();
	}

	@Test
	void fromReturnsNullProfileImageUrlWhenProfileFileIdDoesNotExist() {
		User user = user(77L, "friend");
		ReflectionTestUtils.setField(user, "lastActiveAt", NOW.minusMinutes(1));

		FriendResponse response = FriendResponse.from(user, NOW);

		assertThat(response.profileImageUrl()).isNull();
	}

	@Test
	void fromMarksInactiveWhenLastActiveAtIsNullOrOlderThanFiveMinutes() {
		User missingLastActiveAt = user(77L, "missing");
		User olderThanFiveMinutes = user(78L, "old");
		ReflectionTestUtils.setField(olderThanFiveMinutes, "lastActiveAt", NOW.minusMinutes(5).minusNanos(1));

		assertThat(FriendResponse.from(missingLastActiveAt, NOW).active()).isFalse();
		assertThat(FriendResponse.from(olderThanFiveMinutes, NOW).active()).isFalse();
	}

	@Test
	void fromMarksActiveWhenLastActiveAtIsExactlyFiveMinutesAgo() {
		User user = user(77L, "friend");
		ReflectionTestUtils.setField(user, "lastActiveAt", NOW.minusMinutes(5));

		FriendResponse response = FriendResponse.from(user, NOW);

		assertThat(response.active()).isTrue();
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
