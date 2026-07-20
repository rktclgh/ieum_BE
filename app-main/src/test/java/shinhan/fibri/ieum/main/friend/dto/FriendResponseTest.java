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

	private static final OffsetDateTime LAST_ACTIVE_AT = OffsetDateTime.parse("2026-07-08T12:00:00+09:00");

	@Test
	void fromMapsUserFieldsAndProfileImageUrl() {
		User user = user(77L, "friend");
		user.linkProfileImage(UUID.fromString("11111111-1111-1111-1111-111111111111"));
		ReflectionTestUtils.setField(user, "lastActiveAt", LAST_ACTIVE_AT);

		FriendResponse response = FriendResponse.from(user, true);

		assertThat(response.userId()).isEqualTo(77L);
		assertThat(response.nickname()).isEqualTo("friend");
		assertThat(response.profileImageUrl()).isEqualTo("/api/v1/files/11111111-1111-1111-1111-111111111111");
		assertThat(response.nationality()).isEqualTo("KR");
		assertThat(response.lastActiveAt()).isEqualTo(LAST_ACTIVE_AT);
		assertThat(response.active()).isTrue();
	}

	@Test
	void fromReturnsNullNationalityWhenUserHasNone() {
		User user = user(77L, "friend");
		ReflectionTestUtils.setField(user, "nationality", null);

		FriendResponse response = FriendResponse.from(user, false);

		assertThat(response.nationality()).isNull();
	}

	@Test
	void fromReturnsNullProfileImageUrlWhenProfileFileIdDoesNotExist() {
		User user = user(77L, "friend");
		ReflectionTestUtils.setField(user, "lastActiveAt", LAST_ACTIVE_AT);

		FriendResponse response = FriendResponse.from(user, false);

		assertThat(response.profileImageUrl()).isNull();
	}

	@Test
	void fromUsesResolvedActiveFlagWhenLastActiveAtIsNullOrStale() {
		User missingLastActiveAt = user(77L, "missing");
		User staleLastActiveAt = user(78L, "old");
		ReflectionTestUtils.setField(staleLastActiveAt, "lastActiveAt", LAST_ACTIVE_AT);

		assertThat(FriendResponse.from(missingLastActiveAt, true).active()).isTrue();
		assertThat(FriendResponse.from(staleLastActiveAt, false).active()).isFalse();
	}

	@Test
	void fromCanReturnInactiveEvenWhenLastActiveAtExists() {
		User user = user(77L, "friend");
		ReflectionTestUtils.setField(user, "lastActiveAt", LAST_ACTIVE_AT);

		FriendResponse response = FriendResponse.from(user, false);

		assertThat(response.lastActiveAt()).isEqualTo(LAST_ACTIVE_AT);
		assertThat(response.active()).isFalse();
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
