package shinhan.fibri.ieum.main.notification.sse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import shinhan.fibri.ieum.main.notification.domain.NotificationType;

class OutboundEventTest {

	@Test
	void distinguishesDurableEphemeralAndHeartbeatEvents() {
		NotificationSsePayload durable = NotificationSsePayload.durable(
			1L,
			NotificationType.question,
			"새 답변",
			null,
			3L,
			OffsetDateTime.parse("2026-07-10T12:00:00+09:00")
		);
		NotificationSsePayload ephemeral = NotificationSsePayload.ephemeral(
			NotificationType.location,
			"주변 질문",
			null,
			4L,
			OffsetDateTime.parse("2026-07-10T12:00:00+09:00")
		);

		assertThat(OutboundEvent.durable(durable).kind()).isEqualTo(OutboundEvent.Kind.durable);
		assertThat(OutboundEvent.ephemeral(ephemeral).kind()).isEqualTo(OutboundEvent.Kind.ephemeral);
		assertThat(OutboundEvent.heartbeat().kind()).isEqualTo(OutboundEvent.Kind.heartbeat);
		assertThat(OutboundEvent.heartbeat().payload()).isNull();
	}

	@Test
	void rejectsPayloadWithWrongDeliveryKind() {
		NotificationSsePayload ephemeral = NotificationSsePayload.ephemeral(
			NotificationType.location,
			"주변 질문",
			null,
			4L,
			OffsetDateTime.parse("2026-07-10T12:00:00+09:00")
		);

		assertThatThrownBy(() -> OutboundEvent.durable(ephemeral))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("durable");
	}
}
