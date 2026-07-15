package shinhan.fibri.ieum.main.notification.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.interaso.webpush.WebPush;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class WebPushProviderRequestTest {

	@Test
	void rejectsNegativeTtlAndInvalidTopic() {
		assertThatThrownBy(() -> request(-1, "chat-room"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("ttlSeconds");
		assertThatThrownBy(() -> request(300, "not a topic"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("topic");
		assertThatThrownBy(() -> request(300, "a".repeat(33)))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("topic");
	}

	@Test
	void rejectsEndpointFragmentsAndNonHttpsDefaultPorts() {
		assertThatThrownBy(() -> requestWithEndpoint("https://push.example.test/message#fragment"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("endpoint");
		assertThatThrownBy(() -> requestWithEndpoint("https://push.example.test:8443/message"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("endpoint");
	}

	@Test
	void acceptsAbsentOrBase64UrlSafeTopic() {
		assertThat(request(0, null).topic()).isNull();
		assertThat(request(300, "room_42-hash").topic()).isEqualTo("room_42-hash");
	}

	@Test
	void diagnosticsRedactEndpointKeysAndPayload() {
		WebPushProviderRequest request = request(300, "chat-room");

		assertThat(request.toString())
			.doesNotContain(request.endpoint())
			.doesNotContain(request.p256dh())
			.doesNotContain(request.auth())
			.doesNotContain("payload-secret")
			.contains("endpoint=<redacted>", "p256dh=<redacted>", "auth=<redacted>", "payload=<redacted>");
	}

	private static WebPushProviderRequest request(int ttlSeconds, String topic) {
		return request("https://push.example.test/message/secret-endpoint", ttlSeconds, topic);
	}

	private static WebPushProviderRequest requestWithEndpoint(String endpoint) {
		return request(endpoint, 300, "chat-room");
	}

	private static WebPushProviderRequest request(String endpoint, int ttlSeconds, String topic) {
		return new WebPushProviderRequest(
			"payload-secret".getBytes(StandardCharsets.UTF_8),
			endpoint,
			WebPushTestKeys.generateSubscriptionP256dh(),
			WebPushTestKeys.authSecret(),
			ttlSeconds,
			topic,
			WebPush.Urgency.Normal
		);
	}
}
