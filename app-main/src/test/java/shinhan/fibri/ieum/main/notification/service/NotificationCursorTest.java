package shinhan.fibri.ieum.main.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import shinhan.fibri.ieum.main.notification.exception.InvalidNotificationCursorException;

class NotificationCursorTest {

	@Test
	void encodesAndDecodesEpochMicrosAndNotificationId() {
		OffsetDateTime createdAt = OffsetDateTime.parse("2026-07-10T12:34:56.123456+09:00");

		String encoded = NotificationCursor.encode(createdAt, 123L);
		NotificationCursor decoded = NotificationCursor.decode(encoded);

		assertThat(decoded.createdAt().toInstant()).isEqualTo(createdAt.toInstant());
		assertThat(decoded.notificationId()).isEqualTo(123L);
	}

	@Test
	void returnsNullForBlankCursor() {
		assertThat(NotificationCursor.decode(" ")).isNull();
	}

	@Test
	void rejectsMalformedCursor() {
		assertThatThrownBy(() -> NotificationCursor.decode("not-a-cursor"))
			.isInstanceOf(InvalidNotificationCursorException.class)
			.hasMessage("Invalid cursor");
	}
}
