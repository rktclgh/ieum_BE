package shinhan.fibri.ieum.common.chat.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import shinhan.fibri.ieum.common.auth.domain.GenderType;
import shinhan.fibri.ieum.common.auth.domain.User;

class ChatMemberTest {

	@Test
	void chatMemberIdUsesRoomIdAndUserIdForEquality() {
		assertThat(new ChatMemberId(1L, 2L))
			.isEqualTo(new ChatMemberId(1L, 2L))
			.hasSameHashCodeAs(new ChatMemberId(1L, 2L))
			.isNotEqualTo(new ChatMemberId(1L, 3L));
	}

	@Test
	void leaveRejoinReadPinAndNotifyUpdateMemberState() {
		ChatRoom room = ChatRoom.direct(1L, 2L);
		User user = user("chat-member@example.com", "chat-member");
		ChatMember member = ChatMember.join(room, user);
		OffsetDateTime now = OffsetDateTime.parse("2026-07-08T10:00:00+09:00");

		member.leave(now);
		assertThat(member.isActive()).isFalse();
		assertThat(member.getLeftAt()).isEqualTo(now);

		member.rejoin();
		assertThat(member.isActive()).isTrue();
		assertThat(member.getLeftAt()).isNull();

		member.markRead(now.plusMinutes(1));
		member.setPinned(true, now.plusMinutes(2));
		member.setNotifyEnabled(false);

		assertThat(member.getLastReadAt()).isEqualTo(now.plusMinutes(1));
		assertThat(member.getPinnedAt()).isEqualTo(now.plusMinutes(2));
		assertThat(member.isNotifyEnabled()).isFalse();

		member.setPinned(false, now.plusMinutes(3));
		assertThat(member.getPinnedAt()).isNull();
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
