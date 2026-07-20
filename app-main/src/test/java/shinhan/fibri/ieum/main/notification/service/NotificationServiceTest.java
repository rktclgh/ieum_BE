package shinhan.fibri.ieum.main.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;
import shinhan.fibri.ieum.main.notification.domain.Notification;
import shinhan.fibri.ieum.main.notification.domain.NotificationType;
import shinhan.fibri.ieum.main.notification.dto.NotificationDeleteAllResponse;
import shinhan.fibri.ieum.main.notification.dto.NotificationListResponse;
import shinhan.fibri.ieum.main.notification.dto.NotificationReadAllResponse;
import shinhan.fibri.ieum.main.notification.exception.InvalidNotificationCursorException;
import shinhan.fibri.ieum.main.notification.exception.NotificationNotFoundException;
import shinhan.fibri.ieum.main.notification.repository.NotificationRepository;

class NotificationServiceTest {

	private final NotificationRepository notificationRepository = mock(NotificationRepository.class);
	private final NotificationService service = new NotificationService(notificationRepository);

	@Test
	void listsFirstPageWithLookaheadCursorAndUnreadCount() {
		Notification newest = notification(30L, "newest", "2026-07-10T12:00:00+09:00");
		Notification second = notification(20L, "second", "2026-07-10T11:00:00+09:00");
		Notification lookahead = notification(10L, "lookahead", "2026-07-10T10:00:00+09:00");
		when(notificationRepository.findByUserIdOrderByCreatedAtDescIdDesc(42L, PageRequest.of(0, 3)))
			.thenReturn(List.of(newest, second, lookahead));
		when(notificationRepository.countUnreadByUserId(42L)).thenReturn(4L);

		NotificationListResponse response = service.list(42L, null, 2);

		assertThat(response.items()).extracting(item -> item.notificationId()).containsExactly(30L, 20L);
		NotificationCursor nextCursor = NotificationCursor.decode(response.nextCursor());
		assertThat(nextCursor.createdAt().toInstant()).isEqualTo(second.getCreatedAt().toInstant());
		assertThat(nextCursor.notificationId()).isEqualTo(second.getId());
		assertThat(response.unreadCount()).isEqualTo(4L);
		verify(notificationRepository).findByUserIdOrderByCreatedAtDescIdDesc(42L, PageRequest.of(0, 3));
		verify(notificationRepository).countUnreadByUserId(42L);
	}

	@Test
	void listsPageAfterDecodedCursorAndClampsSizeToFifty() {
		OffsetDateTime cursorCreatedAt = OffsetDateTime.parse("2026-07-10T12:00:00+09:00");
		OffsetDateTime decodedCursorCreatedAt = cursorCreatedAt.withOffsetSameInstant(ZoneOffset.UTC);
		String cursor = NotificationCursor.encode(cursorCreatedAt, 100L);
		when(notificationRepository.findPage(42L, decodedCursorCreatedAt, 100L, PageRequest.of(0, 51)))
			.thenReturn(List.of());
		when(notificationRepository.countUnreadByUserId(42L)).thenReturn(0L);

		NotificationListResponse response = service.list(42L, cursor, 100);

		assertThat(response.items()).isEmpty();
		assertThat(response.nextCursor()).isNull();
		verify(notificationRepository).findPage(42L, decodedCursorCreatedAt, 100L, PageRequest.of(0, 51));
	}

	@Test
	void rejectsInvalidCursorBeforeQueryingRepository() {
		assertThatThrownBy(() -> service.list(42L, "invalid", 20))
			.isInstanceOf(InvalidNotificationCursorException.class);

		verifyNoInteractions(notificationRepository);
	}

	@Test
	void marksExistingNotificationReadIdempotently() {
		when(notificationRepository.markReadByIdAndUserId(10L, 42L)).thenReturn(1);

		service.markRead(42L, 10L);

		verify(notificationRepository).markReadByIdAndUserId(10L, 42L);
	}

	@Test
	void rejectsMarkReadForMissingOrOtherUsersNotification() {
		when(notificationRepository.markReadByIdAndUserId(10L, 42L)).thenReturn(0);

		assertThatThrownBy(() -> service.markRead(42L, 10L))
			.isInstanceOf(NotificationNotFoundException.class);
	}

	@Test
	void marksOnlyUnreadNotificationsForUser() {
		when(notificationRepository.markAllRead(42L)).thenReturn(3);

		NotificationReadAllResponse response = service.markAllRead(42L);

		assertThat(response.updated()).isEqualTo(3);
		verify(notificationRepository).markAllRead(42L);
	}

	@Test
	void deletesOnlyOwnedNotification() {
		when(notificationRepository.deleteByIdAndUserId(10L, 42L)).thenReturn(1);

		service.delete(42L, 10L);

		verify(notificationRepository).deleteByIdAndUserId(10L, 42L);
	}

	@Test
	void rejectsDeleteForMissingOrOtherUsersNotification() {
		when(notificationRepository.deleteByIdAndUserId(10L, 42L)).thenReturn(0);

		assertThatThrownBy(() -> service.delete(42L, 10L))
			.isInstanceOf(NotificationNotFoundException.class);
	}

	@Test
	void deletesEveryNotificationOwnedByUser() {
		when(notificationRepository.deleteAllByUserId(42L)).thenReturn(7);

		NotificationDeleteAllResponse response = service.deleteAll(42L);

		assertThat(response.deleted()).isEqualTo(7);
		verify(notificationRepository).deleteAllByUserId(42L);
	}

	@Test
	void reportsZeroDeletedInsteadOfFailingWhenNothingToDelete() {
		when(notificationRepository.deleteAllByUserId(42L)).thenReturn(0);

		assertThat(service.deleteAll(42L).deleted()).isZero();
	}

	private static Notification notification(Long id, String title, String createdAt) {
		Notification notification = Notification.of(42L, NotificationType.question, title, "body", 7L);
		ReflectionTestUtils.setField(notification, "id", id);
		ReflectionTestUtils.setField(notification, "createdAt", OffsetDateTime.parse(createdAt));
		return notification;
	}
}
