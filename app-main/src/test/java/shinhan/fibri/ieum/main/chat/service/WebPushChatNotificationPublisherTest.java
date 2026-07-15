package shinhan.fibri.ieum.main.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interaso.webpush.WebPush;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import shinhan.fibri.ieum.common.chat.repository.ChatMemberRepository;
import shinhan.fibri.ieum.main.notification.push.WebPushDispatchRequest;
import shinhan.fibri.ieum.main.notification.push.WebPushDispatcher;
import shinhan.fibri.ieum.main.notification.push.WebPushPayloadEncoder;

class WebPushChatNotificationPublisherTest {

	private final ChatMemberRepository chatMemberRepository = mock(ChatMemberRepository.class);
	private final WebPushDispatcher dispatcher = mock(WebPushDispatcher.class);
	private final ObjectMapper objectMapper = new ObjectMapper();
	private final WebPushPayloadEncoder encoder = new WebPushPayloadEncoder(objectMapper);
	private final WebPushChatNotificationPublisher publisher = new WebPushChatNotificationPublisher(
		chatMemberRepository,
		encoder,
		dispatcher
	);

	@Test
	void dispatchesOneGenericPrivacySafeRequestToEachEligibleRecipient() throws Exception {
		when(chatMemberRepository.findPushRecipientUserIds(100L, 42L, 501L)).thenReturn(List.of(77L, 88L));

		publisher.messageCreated(new ChatPushTrigger(501L, 100L, 42L));

		ArgumentCaptor<WebPushDispatchRequest> requestCaptor = ArgumentCaptor.forClass(WebPushDispatchRequest.class);
		verify(dispatcher).dispatch(eq(77L), requestCaptor.capture());
		verify(dispatcher).dispatch(eq(88L), requestCaptor.capture());
		WebPushDispatchRequest request = requestCaptor.getAllValues().getFirst();
		JsonNode payload = objectMapper.readTree(request.payload());
		Set<String> fieldNames = iterable(payload.fieldNames()).stream().collect(Collectors.toSet());
		assertThat(fieldNames).containsExactlyInAnyOrder("version", "kind", "title", "body", "url", "tag");
		assertThat(payload.path("version").asInt()).isEqualTo(1);
		assertThat(payload.path("kind").asText()).isEqualTo("chat");
		assertThat(payload.path("title").asText()).isEqualTo("새 메시지");
		assertThat(payload.path("body").asText()).isEqualTo("새 채팅 메시지가 도착했어요");
		assertThat(payload.path("url").asText()).isEqualTo("/chats/room/?chatId=100");
		assertThat(request.ttlSeconds()).isEqualTo(300);
		assertThat(request.urgency()).isEqualTo(WebPush.Urgency.Normal);
		assertThat(request.topic()).matches("[A-Za-z0-9_-]{32}");
		assertThat(payload.path("tag").asText()).isEqualTo(request.topic());
		assertThat(requestCaptor.getAllValues().get(1)).isSameAs(request);
	}

	@Test
	void minimalTriggerCannotCarryMessageContentImageOrSenderNickname() {
		assertThat(Arrays.stream(ChatPushTrigger.class.getRecordComponents())
			.map(RecordComponent::getName))
			.containsExactly("messageId", "roomId", "senderId");
	}

	@Test
	void sameRoomUsesSameTopicAndDifferentRoomUsesDifferentTopic() {
		when(chatMemberRepository.findPushRecipientUserIds(anyLong(), eq(42L), anyLong())).thenReturn(List.of(77L));

		publisher.messageCreated(new ChatPushTrigger(501L, 100L, 42L));
		publisher.messageCreated(new ChatPushTrigger(502L, 100L, 42L));
		publisher.messageCreated(new ChatPushTrigger(503L, 101L, 42L));

		ArgumentCaptor<WebPushDispatchRequest> captor = ArgumentCaptor.forClass(WebPushDispatchRequest.class);
		verify(dispatcher, org.mockito.Mockito.times(3)).dispatch(eq(77L), captor.capture());
		assertThat(captor.getAllValues().get(0).topic()).isEqualTo(captor.getAllValues().get(1).topic());
		assertThat(captor.getAllValues().get(2).topic()).isNotEqualTo(captor.getAllValues().get(0).topic());
	}

	@Test
	void emptyRecipientListAvoidsEncodingAndDispatch() {
		WebPushPayloadEncoder mockedEncoder = mock(WebPushPayloadEncoder.class);
		WebPushChatNotificationPublisher emptyPublisher = new WebPushChatNotificationPublisher(
			chatMemberRepository,
			mockedEncoder,
			dispatcher
		);
		when(chatMemberRepository.findPushRecipientUserIds(100L, 42L, 501L)).thenReturn(List.of());

		emptyPublisher.messageCreated(new ChatPushTrigger(501L, 100L, 42L));

		verify(mockedEncoder, never()).encode(any());
		verify(dispatcher, never()).dispatch(anyLong(), any());
	}

	@Test
	void oneRecipientFailureDoesNotBlockLaterRecipientsOrEscape() {
		when(chatMemberRepository.findPushRecipientUserIds(100L, 42L, 501L)).thenReturn(List.of(77L, 88L));
		doThrow(new IllegalStateException("secret provider detail"))
			.when(dispatcher).dispatch(eq(77L), any(WebPushDispatchRequest.class));

		assertThatCode(() -> publisher.messageCreated(new ChatPushTrigger(501L, 100L, 42L)))
			.doesNotThrowAnyException();

		verify(dispatcher).dispatch(eq(88L), any(WebPushDispatchRequest.class));
	}

	@Test
	void recipientLookupRunsInNewReadOnlyTransaction() throws Exception {
		Transactional transactional = WebPushChatNotificationPublisher.class
			.getMethod("messageCreated", ChatPushTrigger.class)
			.getAnnotation(Transactional.class);

		assertThat(transactional).isNotNull();
		assertThat(transactional.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
		assertThat(transactional.readOnly()).isTrue();
	}

	private static <T> List<T> iterable(java.util.Iterator<T> iterator) {
		java.util.ArrayList<T> values = new java.util.ArrayList<>();
		iterator.forEachRemaining(values::add);
		return values;
	}
}
