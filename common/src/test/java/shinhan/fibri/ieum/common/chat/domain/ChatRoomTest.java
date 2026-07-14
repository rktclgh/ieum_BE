package shinhan.fibri.ieum.common.chat.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ChatRoomTest {

	@Test
	void createsDirectRoomKeyWithSmallerUserIdFirst() {
		assertThat(ChatRoom.directRoomKey(30L, 10L)).isEqualTo("d:10:30");
		assertThat(ChatRoom.directRoomKey(10L, 30L)).isEqualTo("d:10:30");
	}

	@Test
	void rejectsDirectRoomKeyForSameUser() {
		assertThatThrownBy(() -> ChatRoom.directRoomKey(10L, 10L))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("direct room participants must be different");
	}

	@Test
	void createsRoomTypesWithStableKeys() {
		ChatRoom direct = ChatRoom.direct(2L, 5L);
		ChatRoom group = ChatRoom.group(7L);
		ChatRoom question = ChatRoom.question(11L, 8L, 3L);

		assertThat(direct.getRoomType()).isEqualTo(RoomType.direct);
		assertThat(direct.getRoomKey()).isEqualTo("d:2:5");
		assertThat(direct.getMeetingId()).isNull();
		assertThat(direct.getQuestionId()).isNull();

		assertThat(group.getRoomType()).isEqualTo(RoomType.group);
		assertThat(group.getMeetingId()).isEqualTo(7L);
		assertThat(group.getRoomKey()).isNull();

		assertThat(question.getRoomType()).isEqualTo(RoomType.question);
		assertThat(question.getQuestionId()).isEqualTo(11L);
		assertThat(question.getRoomKey()).isEqualTo("q:11:3:8");
	}
}
