package shinhan.fibri.ieum.main.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interaso.webpush.WebPush;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import shinhan.fibri.ieum.common.chat.repository.ChatMemberRepository;
import shinhan.fibri.ieum.main.notification.message.NotificationLanguageResolver;
import shinhan.fibri.ieum.main.notification.push.WebPushDispatchRequest;
import shinhan.fibri.ieum.main.notification.push.WebPushDispatcher;
import shinhan.fibri.ieum.main.notification.push.WebPushPayloadEncoder;

class WebPushChatNotificationPublisherTest {

	private final ChatMemberRepository chatMemberRepository = mock(ChatMemberRepository.class);
	private final WebPushDispatcher dispatcher = mock(WebPushDispatcher.class);
	private final ObjectMapper objectMapper = new ObjectMapper();
	private final WebPushPayloadEncoder encoder = new WebPushPayloadEncoder(objectMapper);
	private final NotificationLanguageResolver languageResolver = mock(NotificationLanguageResolver.class);
	private final WebPushChatNotificationPublisher publisher = new WebPushChatNotificationPublisher(
		chatMemberRepository,
		encoder,
		dispatcher,
		languageResolver
	);

	// doAnswer/doReturn 을 쓰는 이유: when(mock.resolveAll(any())) 형태로 재스텁하면 Mockito 가
	// 스텁 등록 과정에서 기존 Answer 를 null 인자로 한 번 실행해버려 NPE 가 난다.
	@BeforeEach
	void everyRecipientDefaultsToKorean() {
		doAnswer(invocation -> {
			Collection<Long> userIds = invocation.getArgument(0);
			return userIds == null
				? Map.of()
				: userIds.stream().collect(Collectors.toMap(Function.identity(), userId -> "ko"));
		}).when(languageResolver).resolveAll(any());
	}

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
		assertThat(fieldNames).containsExactlyInAnyOrder(
			"version", "kind", "title", "body", "messageKey", "messageParams", "lang", "url", "tag"
		);
		assertThat(payload.path("version").asInt()).isEqualTo(1);
		assertThat(payload.path("kind").asText()).isEqualTo("chat");
		assertThat(payload.path("messageKey").asText()).isEqualTo("notification.chat.message");
		assertThat(payload.path("lang").asText()).isEqualTo("ko");
		// ko 폴백은 계속 실린다 — 키 렌더를 모르는 구버전 서비스워커가 이걸 쓴다.
		assertThat(payload.path("title").asText()).isEqualTo("새 메시지");
		assertThat(payload.path("body").asText()).isEqualTo("새 채팅 메시지가 도착했어요");
		assertThat(payload.path("url").asText()).isEqualTo("/chats/room/?chatId=100");
		assertThat(request.ttlSeconds()).isEqualTo(300);
		assertThat(request.urgency()).isEqualTo(WebPush.Urgency.Normal);
		assertThat(request.topic()).matches("[A-Za-z0-9_-]{32}");
		assertThat(payload.path("tag").asText()).isEqualTo(request.topic());
		assertThat(requestCaptor.getAllValues().get(1)).isSameAs(request);
	}

	/**
	 * lang 이 페이로드에 들어가면서 "한 번 인코딩해 전원 재사용"이 불가능해졌다. 그렇다고 수신자
	 * 수만큼 인코딩하면 1GB RAM 환경에 부담이므로 <b>언어 종류 수만큼만</b> 인코딩해야 한다.
	 */
	@Test
	void encodesOncePerLanguageNotOncePerRecipient() {
		WebPushPayloadEncoder countingEncoder = spy(encoder);
		doReturn(Map.of(77L, "en", 88L, "en", 99L, "ja")).when(languageResolver).resolveAll(any());
		WebPushChatNotificationPublisher grouped = new WebPushChatNotificationPublisher(
			chatMemberRepository,
			countingEncoder,
			dispatcher,
			languageResolver
		);
		when(chatMemberRepository.findPushRecipientUserIds(100L, 42L, 501L)).thenReturn(List.of(77L, 88L, 99L));

		grouped.messageCreated(new ChatPushTrigger(501L, 100L, 42L));

		// 수신자 3명 · 언어 2종 → 인코딩 2회
		verify(countingEncoder, times(2)).encode(any());
		verify(dispatcher, times(3)).dispatch(anyLong(), any());
	}

	@Test
	void recipientsSharingLanguageShareTheSameEncodedRequest() {
		doReturn(Map.of(77L, "en", 88L, "en", 99L, "ja")).when(languageResolver).resolveAll(any());
		when(chatMemberRepository.findPushRecipientUserIds(100L, 42L, 501L)).thenReturn(List.of(77L, 88L, 99L));

		publisher.messageCreated(new ChatPushTrigger(501L, 100L, 42L));

		ArgumentCaptor<WebPushDispatchRequest> english = ArgumentCaptor.forClass(WebPushDispatchRequest.class);
		verify(dispatcher).dispatch(eq(77L), english.capture());
		verify(dispatcher).dispatch(eq(88L), english.capture());
		assertThat(english.getAllValues().get(0)).isSameAs(english.getAllValues().get(1));

		ArgumentCaptor<WebPushDispatchRequest> japanese = ArgumentCaptor.forClass(WebPushDispatchRequest.class);
		verify(dispatcher).dispatch(eq(99L), japanese.capture());
		assertThat(japanese.getValue()).isNotSameAs(english.getAllValues().getFirst());
	}

	@Test
	void carriesRecipientLanguageInPayload() throws Exception {
		doReturn(Map.of(77L, "vi")).when(languageResolver).resolveAll(any());
		when(chatMemberRepository.findPushRecipientUserIds(100L, 42L, 501L)).thenReturn(List.of(77L));

		publisher.messageCreated(new ChatPushTrigger(501L, 100L, 42L));

		ArgumentCaptor<WebPushDispatchRequest> captor = ArgumentCaptor.forClass(WebPushDispatchRequest.class);
		verify(dispatcher).dispatch(eq(77L), captor.capture());
		assertThat(objectMapper.readTree(captor.getValue().payload()).path("lang").asText()).isEqualTo("vi");
	}

	/**
	 * sw.js 는 {@code payload.version !== 1} 이면 폴백 알림으로 떨어지고, 서비스워커는 캐시돼 있어
	 * 즉시 갱신되지 않는다. 버전을 올리면 구 SW 사용자가 전부 폴백 문구만 보게 되므로 1을 유지한다.
	 */
	@Test
	void keepsPayloadVersionAtOneSoCachedServiceWorkersDoNotFallBack() throws Exception {
		when(chatMemberRepository.findPushRecipientUserIds(100L, 42L, 501L)).thenReturn(List.of(77L));

		publisher.messageCreated(new ChatPushTrigger(501L, 100L, 42L));

		ArgumentCaptor<WebPushDispatchRequest> captor = ArgumentCaptor.forClass(WebPushDispatchRequest.class);
		verify(dispatcher).dispatch(eq(77L), captor.capture());
		assertThat(objectMapper.readTree(captor.getValue().payload()).path("version").asInt()).isEqualTo(1);
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
		verify(dispatcher, times(3)).dispatch(eq(77L), captor.capture());
		assertThat(captor.getAllValues().get(0).topic()).isEqualTo(captor.getAllValues().get(1).topic());
		assertThat(captor.getAllValues().get(2).topic()).isNotEqualTo(captor.getAllValues().get(0).topic());
	}

	@Test
	void emptyRecipientListAvoidsEncodingAndDispatch() {
		WebPushPayloadEncoder mockedEncoder = mock(WebPushPayloadEncoder.class);
		WebPushChatNotificationPublisher emptyPublisher = new WebPushChatNotificationPublisher(
			chatMemberRepository,
			mockedEncoder,
			dispatcher,
			languageResolver
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
