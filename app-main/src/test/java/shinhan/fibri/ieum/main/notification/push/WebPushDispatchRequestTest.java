package shinhan.fibri.ieum.main.notification.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.interaso.webpush.WebPush;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class WebPushDispatchRequestTest {

	@Test
	void defensivelyCopiesPayloadAndRedactsDiagnostics() {
		byte[] source = "secret-payload".getBytes(StandardCharsets.UTF_8);
		WebPushDispatchRequest request = new WebPushDispatchRequest(
			source,
			300,
			"chat-topic",
			WebPush.Urgency.Normal
		);

		source[0] = 'X';
		byte[] returned = request.payload();
		returned[1] = 'Y';

		assertThat(new String(request.payload(), StandardCharsets.UTF_8)).isEqualTo("secret-payload");
		assertThat(request.toString())
			.contains("payload=<redacted>", "ttlSeconds=300", "topic=chat-topic", "urgency=Normal")
			.doesNotContain("secret-payload");
	}

	@Test
	void rejectsInvalidDeliveryMetadata() {
		byte[] payload = "payload".getBytes(StandardCharsets.UTF_8);

		assertThatThrownBy(() -> new WebPushDispatchRequest(payload, -1, null, WebPush.Urgency.Normal))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new WebPushDispatchRequest(payload, 0, "bad topic", WebPush.Urgency.Normal))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new WebPushDispatchRequest(payload, 0, "a".repeat(33), WebPush.Urgency.Normal))
			.isInstanceOf(IllegalArgumentException.class);
	}
}
