package shinhan.fibri.ieum.main.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import shinhan.fibri.ieum.main.notification.domain.Notification;
import shinhan.fibri.ieum.main.notification.domain.NotificationType;
import shinhan.fibri.ieum.main.notification.repository.NotificationEventRepository;
import shinhan.fibri.ieum.main.notification.repository.NotificationRepository;
import shinhan.fibri.ieum.main.notification.sse.OutboundEvent;
import shinhan.fibri.ieum.main.notification.sse.SseConnectionRegistry;

class DatabaseNotificationPublisherTest {

	private final NotificationRepository notificationRepository = mock(NotificationRepository.class);
	private final NotificationEventRepository notificationEventRepository = mock(NotificationEventRepository.class);
	private final SseConnectionRegistry registry = mock(SseConnectionRegistry.class);
	private final NotificationPublisher publisher = new DatabaseNotificationPublisher(
		notificationRepository,
		notificationEventRepository,
		registry
	);

	@Test
	void savesDurableNotificationAndPushesItsDatabaseIdentityAfterCommit() {
		when(notificationRepository.saveAndFlush(any(Notification.class))).thenAnswer(invocation -> {
			Notification notification = invocation.getArgument(0);
			ReflectionTestUtils.setField(notification, "id", 15L);
			ReflectionTestUtils.setField(notification, "createdAt", OffsetDateTime.parse("2026-07-10T12:00:00+09:00"));
			return notification;
		});
		when(registry.isOnline(42L)).thenReturn(true);

		TransactionSynchronizationManager.initSynchronization();
		try {
			publisher.publishDurable(42L, NotificationType.question, "새 답변", "요청 본문", 7L, false);

			org.mockito.ArgumentCaptor<Notification> saved = org.mockito.ArgumentCaptor.forClass(Notification.class);
			verify(notificationRepository).saveAndFlush(saved.capture());
			assertThat(saved.getValue().getAnswerIsAi()).isFalse();
			verify(registry, never()).push(eq(42L), any(OutboundEvent.class));

			TransactionSynchronizationManager.getSynchronizations()
				.forEach(TransactionSynchronization::afterCommit);
		} finally {
			TransactionSynchronizationManager.clearSynchronization();
		}

		org.mockito.ArgumentCaptor<OutboundEvent> event = org.mockito.ArgumentCaptor.forClass(OutboundEvent.class);
		verify(registry).push(eq(42L), event.capture());
		assertThat(event.getValue().kind()).isEqualTo(OutboundEvent.Kind.durable);
		assertThat(event.getValue().payload())
			.extracting(
				payload -> payload.notificationId(),
				payload -> payload.answerIsAi(),
				payload -> payload.createdAt(),
				payload -> payload.persistent()
			)
			.containsExactly(15L, false, OffsetDateTime.parse("2026-07-10T12:00:00+09:00"), true);
	}

	@Test
	void skipsLivePushForOfflineUserWhileStillPersistingDurableNotification() {
		when(notificationRepository.saveAndFlush(any(Notification.class))).thenAnswer(invocation -> {
			Notification notification = invocation.getArgument(0);
			ReflectionTestUtils.setField(notification, "id", 15L);
			ReflectionTestUtils.setField(notification, "createdAt", OffsetDateTime.parse("2026-07-10T12:00:00+09:00"));
			return notification;
		});
		when(registry.isOnline(42L)).thenReturn(false);

		publisher.publishDurable(42L, NotificationType.friend, "친구 요청", null, 7L);

		verify(notificationRepository).saveAndFlush(any(Notification.class));
		verify(registry, never()).push(eq(42L), any(OutboundEvent.class));
	}

	@Test
	void publishesDurableEventOnlyAfterItsIdempotentInsertCommits() {
		OffsetDateTime createdAt = OffsetDateTime.parse("2026-07-10T12:00:00+09:00");
		when(notificationEventRepository.insertOnce(
			42L,
			NotificationType.question,
			"새 답변",
			"회원님의 질문에 답변이 달렸어요",
			7L,
			true,
			"answer-created:15"
		)).thenReturn(Optional.of(new NotificationEventRepository.InsertedNotification(21L, createdAt)));
		when(registry.isOnline(42L)).thenReturn(true);

		TransactionSynchronizationManager.initSynchronization();
		boolean inserted;
		try {
			inserted = publisher.publishDurableOnce(
				42L,
				NotificationType.question,
				"새 답변",
				"회원님의 질문에 답변이 달렸어요",
				7L,
				true,
				"answer-created:15"
			);

			verify(registry, never()).push(eq(42L), any(OutboundEvent.class));
			TransactionSynchronizationManager.getSynchronizations()
				.forEach(TransactionSynchronization::afterCommit);
		} finally {
			TransactionSynchronizationManager.clearSynchronization();
		}

		assertThat(inserted).isTrue();
		org.mockito.ArgumentCaptor<OutboundEvent> event = org.mockito.ArgumentCaptor.forClass(OutboundEvent.class);
		verify(registry).push(eq(42L), event.capture());
		assertThat(event.getValue().payload())
			.extracting(
				payload -> payload.notificationId(),
				payload -> payload.refId(),
				payload -> payload.answerIsAi(),
				payload -> payload.createdAt()
			)
			.containsExactly(21L, 7L, true, createdAt);
	}

	@Test
	void skipsSseWhenIdempotentInsertLosesTheEventKeyConflict() {
		when(notificationEventRepository.insertOnce(
			42L,
			NotificationType.question,
			"새 답변",
			"회원님의 질문에 답변이 달렸어요",
			7L,
			true,
			"answer-created:15"
		)).thenReturn(Optional.empty());

		boolean inserted = publisher.publishDurableOnce(
			42L,
			NotificationType.question,
			"새 답변",
			"회원님의 질문에 답변이 달렸어요",
			7L,
			true,
			"answer-created:15"
		);

		assertThat(inserted).isFalse();
		verify(registry, never()).push(eq(42L), any(OutboundEvent.class));
	}
}
