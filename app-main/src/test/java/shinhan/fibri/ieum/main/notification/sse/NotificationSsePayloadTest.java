package shinhan.fibri.ieum.main.notification.sse;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import shinhan.fibri.ieum.main.notification.domain.NotificationType;

class NotificationSsePayloadTest {

	@Test
	void createsDurablePayloadWithNotificationIdAndPersistentFlag() {
		OffsetDateTime createdAt = OffsetDateTime.parse("2026-07-10T12:00:00+09:00");

		NotificationSsePayload payload = NotificationSsePayload.durable(
			10L,
			NotificationType.question,
			"새 답변",
			"질문에 답변이 달렸어요",
			5L,
			createdAt
		);

		assertThat(payload.notificationId()).isEqualTo(10L);
		assertThat(payload.persistent()).isTrue();
		assertThat(payload.createdAt()).isEqualTo(createdAt);
	}

	@Test
	void createsEphemeralPayloadWithoutNotificationId() {
		NotificationSsePayload payload = NotificationSsePayload.ephemeral(
			NotificationType.meeting,
			"주변 모임",
			"새 모임이 열렸어요",
			8L,
			OffsetDateTime.parse("2026-07-10T12:00:00+09:00")
		);

		assertThat(payload.notificationId()).isNull();
		assertThat(payload.persistent()).isFalse();
	}
}
