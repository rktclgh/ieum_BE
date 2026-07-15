package shinhan.fibri.ieum.main.notification.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class WebPushTransportPropertiesTest {

	private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
	private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

	@Test
	void disabledConfigurationAllowsEmptyCredentials() {
		assertThatCode(() -> new WebPushTransportProperties("", "", CONNECT_TIMEOUT, REQUEST_TIMEOUT))
			.doesNotThrowAnyException();
	}

	@Test
	void rejectsMissingPrivateKeyWhenCreatingVapidKeys() {
		WebPushTestKeys.RawVapidKeys keys = WebPushTestKeys.generateVapidKeys();
		WebPushTransportProperties properties = properties("", "mailto:ops@example.com");

		assertThatThrownBy(() -> properties.createVapidKeys(keys.publicKey()))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("app.web-push.vapid-private-key")
			.hasMessageNotContaining(keys.publicKey());
	}

	@Test
	void rejectsInvalidVapidSubject() {
		WebPushTestKeys.RawVapidKeys keys = WebPushTestKeys.generateVapidKeys();
		WebPushTransportProperties properties = properties(keys.privateKey(), "ftp://example.com");

		assertThatThrownBy(() -> properties.createVapidKeys(keys.publicKey()))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("app.web-push.vapid-subject")
			.hasMessageNotContaining(keys.privateKey());

		assertThatThrownBy(() -> properties(keys.privateKey(), "https:missing-host")
			.createVapidKeys(keys.publicKey()))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("app.web-push.vapid-subject");

		assertThatThrownBy(() -> properties(keys.privateKey(), "mailto:")
			.createVapidKeys(keys.publicKey()))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("app.web-push.vapid-subject");
	}

	@Test
	void rejectsHttpsSubjectWithoutAuthoritySeparator() {
		WebPushTestKeys.RawVapidKeys keys = WebPushTestKeys.generateVapidKeys();
		WebPushTransportProperties properties = properties(keys.privateKey(), "https:ops.example.com");

		assertThatThrownBy(() -> properties.createVapidKeys(keys.publicKey()))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("app.web-push.vapid-subject");
	}

	@Test
	void rejectsUppercaseOrMixedCaseVapidSubjectSchemes() {
		WebPushTestKeys.RawVapidKeys keys = WebPushTestKeys.generateVapidKeys();

		assertThatThrownBy(() -> properties(keys.privateKey(), "MAILTO:ops@example.com")
			.createVapidKeys(keys.publicKey()))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("app.web-push.vapid-subject")
			.hasMessageNotContaining(keys.privateKey());
		assertThatThrownBy(() -> properties(keys.privateKey(), "Https://example.com/contact")
			.createVapidKeys(keys.publicKey()))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("app.web-push.vapid-subject")
			.hasMessageNotContaining(keys.privateKey());
	}

	@Test
	void rejectsMalformedOrMismatchedRawVapidKeysWithoutLeakingThem() {
		WebPushTestKeys.RawVapidKeys publicKeys = WebPushTestKeys.generateVapidKeys();
		WebPushTestKeys.RawVapidKeys privateKeys = WebPushTestKeys.generateVapidKeys();
		WebPushTransportProperties properties = properties(
			privateKeys.privateKey(),
			"https://example.com/web-push-contact"
		);

		assertThatThrownBy(() -> properties.createVapidKeys(publicKeys.publicKey()))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("app.web-push.vapid-public-key")
			.hasMessageContaining("app.web-push.vapid-private-key")
			.hasMessageNotContaining(publicKeys.publicKey())
			.hasMessageNotContaining(privateKeys.privateKey());
	}

	@Test
	void rejectsNonPositiveTimeoutsWithPropertyNameOnlyDiagnostics() {
		assertThatThrownBy(() -> new WebPushTransportProperties(
			"private-secret",
			"mailto:ops@example.com",
			Duration.ZERO,
			REQUEST_TIMEOUT
		))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("app.web-push.connect-timeout")
			.hasMessageNotContaining("private-secret");

		assertThatThrownBy(() -> new WebPushTransportProperties(
			"private-secret",
			"mailto:ops@example.com",
			CONNECT_TIMEOUT,
			Duration.ofSeconds(-1)
		))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("app.web-push.request-timeout")
			.hasMessageNotContaining("private-secret");
	}

	@Test
	void redactsPrivateKeyFromDiagnostics() {
		String privateKey = "private-vapid-key-material";
		WebPushTransportProperties properties = properties(privateKey, "mailto:ops@example.com");

		assertThat(properties.toString())
			.doesNotContain(privateKey)
			.contains("vapidPrivateKey=<redacted>");
	}

	private static WebPushTransportProperties properties(String privateKey, String subject) {
		return new WebPushTransportProperties(privateKey, subject, CONNECT_TIMEOUT, REQUEST_TIMEOUT);
	}
}
