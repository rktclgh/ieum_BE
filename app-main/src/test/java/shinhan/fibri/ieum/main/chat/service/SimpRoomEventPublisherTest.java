package shinhan.fibri.ieum.main.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import shinhan.fibri.ieum.common.auth.domain.GenderType;
import shinhan.fibri.ieum.common.auth.domain.User;
import shinhan.fibri.ieum.common.chat.domain.ChatMember;
import shinhan.fibri.ieum.common.chat.domain.ChatRoom;
import shinhan.fibri.ieum.common.chat.domain.MessageType;
import shinhan.fibri.ieum.main.chat.dto.ChatReplyPreview;

class SimpRoomEventPublisherTest {

	private final SimpMessagingTemplate messagingTemplate = org.mockito.Mockito.mock(SimpMessagingTemplate.class);
	private final SimpRoomEventPublisher publisher = new SimpRoomEventPublisher(messagingTemplate);

	@Test
	void publishesSystemRoomEventToRoomTopic() {
		WsMessageEvent event = new WsMessageEvent(
			501L,
			100L,
			42L,
			"sender",
			null,
			MessageType.system,
			"member left",
			null,
			OffsetDateTime.parse("2026-07-08T12:00:00+09:00")
		);

		publisher.publish(event);

		assertThat(event.messageType()).isEqualTo(MessageType.system);
		verify(messagingTemplate).convertAndSend("/topic/rooms/100", event);
	}

	@Test
	void publishesUserReplyPerRecipientAndRedactsParentBeforeRejoinBoundary() {
		ChatRoom room = room(ChatRoom.direct(42L, 77L), 100L);
		ChatMember rejoinedMember = ChatMember.join(room, user(42L, "rejoined"));
		rejoinedMember.hideHistoryThrough(400L);
		ChatMember uninterruptedMember = ChatMember.join(room, user(77L, "uninterrupted"));
		WsMessageEvent event = new WsMessageEvent(
			501L,
			100L,
			77L,
			"uninterrupted",
			null,
			MessageType.user,
			"visible reply",
			null,
			OffsetDateTime.parse("2026-07-08T12:00:00+09:00"),
			new ChatReplyPreview(400L, 77L, "uninterrupted", "hidden parent", null)
		);
		WsMessageEvent redactedEvent = new WsMessageEvent(
			event.messageId(),
			event.roomId(),
			event.senderId(),
			event.senderNickname(),
			event.senderProfileImageUrl(),
			event.messageType(),
			event.content(),
			event.imageUrl(),
			event.createdAt(),
			null
		);

		publisher.publishUserMessage(event, List.of(rejoinedMember, uninterruptedMember));

		verify(messagingTemplate).convertAndSendToUser("42", "/queue/rooms/100", redactedEvent);
		verify(messagingTemplate).convertAndSendToUser("77", "/queue/rooms/100", event);
		verify(messagingTemplate, never()).convertAndSend("/topic/rooms/100", event);
	}

	private User user(Long id, String nickname) {
		User user = User.createEmailUser(
			nickname + "@example.com",
			"hash",
			nickname,
			LocalDate.of(1995, 1, 1),
			GenderType.female,
			"KR"
		);
		setField(user, "id", id);
		return user;
	}

	private ChatRoom room(ChatRoom room, Long id) {
		setField(room, "id", id);
		return room;
	}

	private void setField(Object target, String fieldName, Object value) {
		try {
			java.lang.reflect.Field field = target.getClass().getDeclaredField(fieldName);
			field.setAccessible(true);
			field.set(target, value);
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException(exception);
		}
	}
}
