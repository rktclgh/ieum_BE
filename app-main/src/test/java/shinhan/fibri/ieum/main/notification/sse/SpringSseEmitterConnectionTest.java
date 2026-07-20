package shinhan.fibri.ieum.main.notification.sse;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import shinhan.fibri.ieum.main.notification.domain.NotificationType;

class SpringSseEmitterConnectionTest {

	@Test
	void sendsPresenceWithPresenceEventNameAndNoSseId() throws Exception {
		CapturingSseEmitter emitter = new CapturingSseEmitter();
		SpringSseEmitterConnection connection = new SpringSseEmitterConnection(emitter);

		connection.send(OutboundEvent.presence(new PresenceSsePayload(42L, true)));

		assertThat(emitter.sentText()).containsExactly("event:presence\ndata:", "\n\n");
		assertThat(emitter.sentPayload()).isEqualTo(new PresenceSsePayload(42L, true));
	}

	@Test
	void sendsDurableNotificationWithNotificationEventNameAndSseId() throws Exception {
		CapturingSseEmitter emitter = new CapturingSseEmitter();
		SpringSseEmitterConnection connection = new SpringSseEmitterConnection(emitter);
		NotificationSsePayload payload = NotificationSsePayload.durable(
			15L,
			NotificationType.question,
			"새 답변",
			null,
			7L,
			OffsetDateTime.parse("2026-07-10T12:00:00+09:00")
		);

		connection.send(OutboundEvent.durable(payload));

		assertThat(emitter.sentText()).containsExactly("id:15\nevent:notification\ndata:", "\n\n");
		assertThat(emitter.sentPayload()).isEqualTo(payload);
	}

	private static final class CapturingSseEmitter extends SseEmitter {

		private final List<ResponseBodyEmitter.DataWithMediaType> sent = new ArrayList<>();

		@Override
		public void send(SseEventBuilder builder) throws IOException {
			sent.addAll(builder.build());
		}

		private List<String> sentText() {
			return sent.stream()
				.map(ResponseBodyEmitter.DataWithMediaType::getData)
				.filter(String.class::isInstance)
				.map(String.class::cast)
				.toList();
		}

		private Object sentPayload() {
			return sent.stream()
				.map(ResponseBodyEmitter.DataWithMediaType::getData)
				.filter(data -> !(data instanceof String))
				.findFirst()
				.orElseThrow();
		}
	}
}
