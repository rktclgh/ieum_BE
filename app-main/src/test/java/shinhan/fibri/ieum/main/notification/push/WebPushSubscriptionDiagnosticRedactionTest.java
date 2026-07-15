package shinhan.fibri.ieum.main.notification.push;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class WebPushSubscriptionDiagnosticRedactionTest {

	private static final String SESSION_ID = "session-secret-value";
	private static final String ENDPOINT = "https://push.example/subscriptions/endpoint-secret-value";
	private static final String P256DH = "p256dh-secret-value";
	private static final String AUTH_SECRET = "auth-secret-value";

	@Test
	void subscriptionDiagnosticTextDoesNotExposeDeliveryCredentialsOrSession() {
		OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
		WebPushSubscription subscription = new WebPushSubscription(
			101L,
			202L,
			SESSION_ID,
			ENDPOINT,
			P256DH,
			AUTH_SECRET,
			3L,
			now.plusDays(1),
			now,
			now
		);

		assertThat(subscription.toString())
			.contains("subscriptionId=101", "userId=202", "bindingVersion=3")
			.doesNotContain(SESSION_ID, ENDPOINT, P256DH, AUTH_SECRET);
	}

	@Test
	void subscriptionInputDiagnosticTextDoesNotExposeDeliveryCredentialsOrSession() {
		WebPushSubscriptionInput input = new WebPushSubscriptionInput(
			202L,
			SESSION_ID,
			ENDPOINT,
			P256DH,
			AUTH_SECRET,
			OffsetDateTime.now(ZoneOffset.UTC).plusDays(1)
		);

		assertThat(input.toString())
			.contains("userId=202")
			.doesNotContain(SESSION_ID, ENDPOINT, P256DH, AUTH_SECRET);
	}
}
