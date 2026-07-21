package shinhan.fibri.ieum.main.notification.sse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.Map;
import org.junit.jupiter.api.Test;
import shinhan.fibri.ieum.main.notification.domain.NotificationType;
import shinhan.fibri.ieum.main.notification.message.NotificationMessage;
import shinhan.fibri.ieum.main.notification.message.NotificationMessageKey;

class OutboundEventTest {

	@Test
	void distinguishesDurableEphemeralAndHeartbeatEvents() {
		NotificationSsePayload durable = NotificationSsePayload.durable(
			1L,
			NotificationType.question,
			NotificationMessage.of(NotificationMessageKey.ANSWER_CREATED),
			"새 답변",
			null,
			3L,
			OffsetDateTime.parse("2026-07-10T12:00:00+09:00")
		);
		NotificationSsePayload ephemeral = NotificationSsePayload.ephemeral(
			NotificationType.location,
			NotificationMessage.of(NotificationMessageKey.RADIUS_QUESTION, Map.of("subject", "주변 질문")),
			"주변 질문",
			null,
			4L,
			OffsetDateTime.parse("2026-07-10T12:00:00+09:00")
		);

		assertThat(OutboundEvent.durable(durable).kind()).isEqualTo(OutboundEvent.Kind.durable);
		assertThat(OutboundEvent.ephemeral(ephemeral).kind()).isEqualTo(OutboundEvent.Kind.ephemeral);
		assertThat(OutboundEvent.heartbeat().kind()).isEqualTo(OutboundEvent.Kind.heartbeat);
		assertThat(OutboundEvent.heartbeat().payload()).isNull();
		assertThat(OutboundEvent.durable(durable).eventName()).isEqualTo("notification");
		assertThat(OutboundEvent.ephemeral(ephemeral).eventName()).isEqualTo("notification");
	}

	@Test
	void createsEphemeralPresenceEventWithPresenceName() {
		OutboundEvent event = OutboundEvent.presence(new PresenceSsePayload(42L, true));

		assertThat(event.kind()).isEqualTo(OutboundEvent.Kind.ephemeral);
		assertThat(event.eventName()).isEqualTo("presence");
		assertThat(event.payload()).isEqualTo(new PresenceSsePayload(42L, true));
		assertThat(event.notificationPayload()).isNull();
	}

	@Test
	void rejectsPayloadWithWrongDeliveryKind() {
		NotificationSsePayload ephemeral = NotificationSsePayload.ephemeral(
			NotificationType.location,
			NotificationMessage.of(NotificationMessageKey.RADIUS_QUESTION, Map.of("subject", "주변 질문")),
			"주변 질문",
			null,
			4L,
			OffsetDateTime.parse("2026-07-10T12:00:00+09:00")
		);

		assertThatThrownBy(() -> OutboundEvent.durable(ephemeral))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("durable");
	}

	@Test
	void rejectsEventNameThatDoesNotMatchPayloadContract() {
		NotificationSsePayload notification = NotificationSsePayload.ephemeral(
			NotificationType.location,
			NotificationMessage.of(NotificationMessageKey.RADIUS_QUESTION, Map.of("subject", "주변 질문")),
			"주변 질문",
			null,
			4L,
			OffsetDateTime.parse("2026-07-10T12:00:00+09:00")
		);

		assertThatThrownBy(() -> new OutboundEvent(
			OutboundEvent.Kind.ephemeral,
			"presence",
			notification
		)).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("notification event name");
		assertThatThrownBy(() -> new OutboundEvent(
			OutboundEvent.Kind.ephemeral,
			"notification",
			new PresenceSsePayload(42L, true)
		)).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("presence event name");
	}

	@Test
	void rejectsHeartbeatWithEventNameOrPayload() {
		assertThatThrownBy(() -> new OutboundEvent(
			OutboundEvent.Kind.heartbeat,
			"notification",
			null
		)).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("heartbeat");
		assertThatThrownBy(() -> new OutboundEvent(
			OutboundEvent.Kind.heartbeat,
			null,
			new PresenceSsePayload(42L, true)
		)).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("heartbeat");
	}
}
