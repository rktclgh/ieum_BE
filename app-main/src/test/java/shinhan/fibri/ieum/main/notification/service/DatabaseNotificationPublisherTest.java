package shinhan.fibri.ieum.main.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.interaso.webpush.WebPush;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import shinhan.fibri.ieum.main.notification.domain.Notification;
import shinhan.fibri.ieum.main.notification.domain.NotificationType;
import shinhan.fibri.ieum.main.notification.push.WebPushDispatchRequest;
import shinhan.fibri.ieum.main.notification.push.WebPushDispatcher;
import shinhan.fibri.ieum.main.notification.push.WebPushPayloadEncoder;
import shinhan.fibri.ieum.main.notification.repository.NotificationEventRepository;
import shinhan.fibri.ieum.main.notification.repository.NotificationRepository;
import shinhan.fibri.ieum.main.notification.sse.OutboundEvent;
import shinhan.fibri.ieum.main.notification.sse.SseConnectionRegistry;

class DatabaseNotificationPublisherTest {

	private static final long USER_ID = 42L;
	private static final long NOTIFICATION_ID = 15L;
	private static final OffsetDateTime CREATED_AT = OffsetDateTime.parse("2026-07-10T12:00:00+09:00");

	private final NotificationRepository notificationRepository = mock(NotificationRepository.class);
	private final NotificationEventRepository notificationEventRepository = mock(NotificationEventRepository.class);
	private final SseConnectionRegistry registry = mock(SseConnectionRegistry.class);
	private final WebPushDispatcher dispatcher = mock(WebPushDispatcher.class);
	private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
	private final WebPushPayloadEncoder encoder = new WebPushPayloadEncoder(objectMapper);
	private final NotificationPublisher publisher = publisher(encoder);

	@Test
	void fansOutDurableNotificationOnlyAfterCommitWithExactPushContract() throws Exception {
		stubSavedNotification();
		when(registry.isOnline(USER_ID)).thenReturn(true);

		TransactionSynchronization synchronization = publishInsideTransaction(
			() -> publisher.publishDurable(
				USER_ID,
				NotificationType.question,
				"새 답변",
				"회원님의 질문에 답변이 달렸어요",
				7L,
				false
			)
		);

		verify(registry, never()).push(eq(USER_ID), any(OutboundEvent.class));
		verifyNoInteractions(dispatcher);

		synchronization.afterCommit();

		ArgumentCaptor<OutboundEvent> event = ArgumentCaptor.forClass(OutboundEvent.class);
		verify(registry).push(eq(USER_ID), event.capture());
		assertThat(event.getValue().kind()).isEqualTo(OutboundEvent.Kind.durable);
		assertThat(event.getValue().notificationPayload())
			.extracting(
				payload -> payload.notificationId(),
				payload -> payload.answerIsAi(),
				payload -> payload.createdAt(),
				payload -> payload.persistent()
			)
			.containsExactly(NOTIFICATION_ID, false, CREATED_AT, true);

		ArgumentCaptor<WebPushDispatchRequest> request = ArgumentCaptor.forClass(WebPushDispatchRequest.class);
		verify(dispatcher).dispatch(eq(USER_ID), request.capture());
		JsonNode payload = objectMapper.readTree(request.getValue().payload());
		assertThat(payload.fieldNames()).toIterable().containsExactlyInAnyOrderElementsOf(Set.of(
			"version",
			"kind",
			"notificationId",
			"type",
			"title",
			"body",
			"refId",
			"answerIsAi"
		));
		assertThat(payload.get("version").asInt()).isEqualTo(1);
		assertThat(payload.get("kind").asText()).isEqualTo("notification");
		assertThat(payload.get("notificationId").asLong()).isEqualTo(NOTIFICATION_ID);
		assertThat(payload.get("type").asText()).isEqualTo("question");
		assertThat(payload.get("title").asText()).isEqualTo("새 답변");
		assertThat(payload.get("body").asText()).isEqualTo("회원님의 질문에 답변이 달렸어요");
		assertThat(payload.get("refId").asLong()).isEqualTo(7L);
		assertThat(payload.get("answerIsAi").asBoolean()).isFalse();
		assertThat(request.getValue().ttlSeconds()).isEqualTo(3_600);
		assertThat(request.getValue().urgency()).isEqualTo(WebPush.Urgency.Normal);
		assertThat(request.getValue().topic()).isEqualTo("notification-15");
	}

	@Test
	void rollbackDeliversNeitherSseNorWebPush() {
		stubSavedNotification();
		when(registry.isOnline(USER_ID)).thenReturn(true);

		TransactionSynchronization synchronization = publishInsideTransaction(
			() -> publisher.publishDurable(USER_ID, NotificationType.friend, "친구 요청", null, 7L)
		);

		synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);

		verify(registry, never()).push(eq(USER_ID), any(OutboundEvent.class));
		verifyNoInteractions(dispatcher);
	}

	@Test
	void preservesExplicitNullFieldsForNonAnswerNotification() throws Exception {
		stubSavedNotification();

		publisher.publishDurable(USER_ID, NotificationType.friend, "친구 요청", null, null);

		ArgumentCaptor<WebPushDispatchRequest> request = ArgumentCaptor.forClass(WebPushDispatchRequest.class);
		verify(dispatcher).dispatch(eq(USER_ID), request.capture());
		JsonNode payload = objectMapper.readTree(request.getValue().payload());
		assertThat(payload.has("body")).isTrue();
		assertThat(payload.get("body").isNull()).isTrue();
		assertThat(payload.has("refId")).isTrue();
		assertThat(payload.get("refId").isNull()).isTrue();
		assertThat(payload.has("answerIsAi")).isTrue();
		assertThat(payload.get("answerIsAi").isNull()).isTrue();
	}

	@Test
	void offlineSseUserStillReceivesWebPush() {
		stubSavedNotification();
		when(registry.isOnline(USER_ID)).thenReturn(false);

		publisher.publishDurable(USER_ID, NotificationType.friend, "친구 요청", null, 7L);

		verify(registry, never()).push(eq(USER_ID), any(OutboundEvent.class));
		verify(dispatcher).dispatch(eq(USER_ID), any(WebPushDispatchRequest.class));
	}

	@Test
	void sseOnlineCheckFailureDoesNotEscapeOrSuppressWebPush() {
		stubSavedNotification();
		when(registry.isOnline(USER_ID)).thenThrow(new IllegalStateException("sensitive registry failure"));

		TransactionSynchronization synchronization = publishInsideTransaction(
			() -> publisher.publishDurable(USER_ID, NotificationType.friend, "친구 요청", null, 7L)
		);

		assertThatCode(synchronization::afterCommit).doesNotThrowAnyException();

		verify(dispatcher).dispatch(eq(USER_ID), any(WebPushDispatchRequest.class));
	}

	@Test
	void ssePushFailureDoesNotEscapeOrSuppressWebPush() {
		stubSavedNotification();
		when(registry.isOnline(USER_ID)).thenReturn(true);
		doThrow(new IllegalStateException("sensitive sse failure"))
			.when(registry).push(eq(USER_ID), any(OutboundEvent.class));

		TransactionSynchronization synchronization = publishInsideTransaction(
			() -> publisher.publishDurable(USER_ID, NotificationType.friend, "친구 요청", null, 7L)
		);

		assertThatCode(synchronization::afterCommit).doesNotThrowAnyException();

		verify(dispatcher).dispatch(eq(USER_ID), any(WebPushDispatchRequest.class));
	}

	@Test
	void encoderFailureDoesNotEscapeOrSuppressSse() {
		stubSavedNotification();
		when(registry.isOnline(USER_ID)).thenReturn(true);
		WebPushPayloadEncoder failingEncoder = mock(WebPushPayloadEncoder.class);
		when(failingEncoder.encode(any())).thenThrow(new IllegalStateException("sensitive payload failure"));
		NotificationPublisher failingPublisher = publisher(failingEncoder);

		TransactionSynchronization synchronization = publishInsideTransaction(
			() -> failingPublisher.publishDurable(USER_ID, NotificationType.friend, "친구 요청", null, 7L)
		);

		assertThatCode(synchronization::afterCommit).doesNotThrowAnyException();

		verify(registry).push(eq(USER_ID), any(OutboundEvent.class));
		verifyNoInteractions(dispatcher);
	}

	@Test
	void dispatcherFailureDoesNotEscapeOrSuppressSse() {
		stubSavedNotification();
		when(registry.isOnline(USER_ID)).thenReturn(true);
		doThrow(new IllegalStateException("sensitive provider failure"))
			.when(dispatcher).dispatch(eq(USER_ID), any(WebPushDispatchRequest.class));

		TransactionSynchronization synchronization = publishInsideTransaction(
			() -> publisher.publishDurable(USER_ID, NotificationType.friend, "친구 요청", null, 7L)
		);

		assertThatCode(synchronization::afterCommit).doesNotThrowAnyException();

		verify(registry).push(eq(USER_ID), any(OutboundEvent.class));
	}

	@Test
	void idempotentInsertWinnerFansOutOnlyAfterCommit() {
		when(notificationEventRepository.insertOnce(
			USER_ID,
			NotificationType.question,
			"새 답변",
			"회원님의 질문에 답변이 달렸어요",
			7L,
			true,
			"answer-created:15"
		)).thenReturn(Optional.of(new NotificationEventRepository.InsertedNotification(21L, CREATED_AT)));
		when(registry.isOnline(USER_ID)).thenReturn(true);

		TransactionSynchronizationManager.initSynchronization();
		boolean inserted;
		TransactionSynchronization synchronization;
		try {
			inserted = publisher.publishDurableOnce(
				USER_ID,
				NotificationType.question,
				"새 답변",
				"회원님의 질문에 답변이 달렸어요",
				7L,
				true,
				"answer-created:15"
			);
			assertThat(TransactionSynchronizationManager.getSynchronizations()).hasSize(1);
			synchronization = TransactionSynchronizationManager.getSynchronizations().getFirst();
		} finally {
			TransactionSynchronizationManager.clearSynchronization();
		}

		assertThat(inserted).isTrue();
		verifyNoInteractions(dispatcher);
		synchronization.afterCommit();

		ArgumentCaptor<OutboundEvent> event = ArgumentCaptor.forClass(OutboundEvent.class);
		verify(registry).push(eq(USER_ID), event.capture());
		assertThat(event.getValue().notificationPayload())
			.extracting(
				payload -> payload.notificationId(),
				payload -> payload.refId(),
				payload -> payload.answerIsAi(),
				payload -> payload.createdAt()
			)
			.containsExactly(21L, 7L, true, CREATED_AT);
		verify(dispatcher).dispatch(eq(USER_ID), any(WebPushDispatchRequest.class));
	}

	@Test
	void idempotentInsertConflictSchedulesNeitherChannel() {
		when(notificationEventRepository.insertOnce(
			USER_ID,
			NotificationType.question,
			"새 답변",
			"회원님의 질문에 답변이 달렸어요",
			7L,
			true,
			"answer-created:15"
		)).thenReturn(Optional.empty());

		boolean inserted = publisher.publishDurableOnce(
			USER_ID,
			NotificationType.question,
			"새 답변",
			"회원님의 질문에 답변이 달렸어요",
			7L,
			true,
			"answer-created:15"
		);

		assertThat(inserted).isFalse();
		verifyNoInteractions(registry, dispatcher);
	}

	@Test
	void ephemeralPublicationRemainsSseOnly() {
		when(registry.isOnline(USER_ID)).thenReturn(true);

		publisher.publishEphemeral(USER_ID, NotificationType.location, "주변 알림", null, 7L);

		verify(registry).push(eq(USER_ID), any(OutboundEvent.class));
		verifyNoInteractions(dispatcher);
	}

	private NotificationPublisher publisher(WebPushPayloadEncoder payloadEncoder) {
		return new DatabaseNotificationPublisher(
			notificationRepository,
			notificationEventRepository,
			registry,
			dispatcher,
			payloadEncoder
		);
	}

	private void stubSavedNotification() {
		when(notificationRepository.saveAndFlush(any(Notification.class))).thenAnswer(invocation -> {
			Notification notification = invocation.getArgument(0);
			ReflectionTestUtils.setField(notification, "id", NOTIFICATION_ID);
			ReflectionTestUtils.setField(notification, "createdAt", CREATED_AT);
			return notification;
		});
	}

	private static TransactionSynchronization publishInsideTransaction(Runnable action) {
		TransactionSynchronizationManager.initSynchronization();
		try {
			action.run();
			assertThat(TransactionSynchronizationManager.getSynchronizations()).hasSize(1);
			return TransactionSynchronizationManager.getSynchronizations().getFirst();
		} finally {
			TransactionSynchronizationManager.clearSynchronization();
		}
	}
}
