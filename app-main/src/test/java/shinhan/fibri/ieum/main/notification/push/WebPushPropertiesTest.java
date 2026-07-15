package shinhan.fibri.ieum.main.notification.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import org.junit.jupiter.api.Test;

class WebPushPropertiesTest {

	@Test
	void disabledModeNormalizesPublicKeyAndAllowsEmptyHosts() {
		WebPushProperties properties = new WebPushProperties(false, "ignored-secret", "");

		assertThat(properties.enabled()).isFalse();
		assertThat(properties.vapidPublicKey()).isEmpty();
		assertThat(properties.allowedEndpointHosts()).isEmpty();
	}

	@Test
	void enabledModeNormalizesAllowedHosts() {
		String publicKey = WebPushTestKeys.generateVapidKeys().publicKey();
		WebPushProperties properties = new WebPushProperties(
			true,
			publicKey,
			" FCM.GOOGLEAPIS.COM, push.services.mozilla.com "
		);

		assertThat(properties.allowedEndpointHosts())
			.isEqualTo(Set.of("fcm.googleapis.com", "push.services.mozilla.com"));
	}

	@Test
	void enabledModeRequiresPublicKeyAndHostAllowList() {
		assertThatThrownBy(() -> new WebPushProperties(true, " ", "fcm.googleapis.com"))
			.isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> new WebPushProperties(
			true,
			WebPushTestKeys.generateVapidKeys().publicKey(),
			" "
		))
			.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void rejectsMalformedEnabledPublicKeyWithoutLeakingIt() {
		String malformed = "not-base64url+secret";

		assertThatThrownBy(() -> new WebPushProperties(true, malformed, "fcm.googleapis.com"))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("app.web-push.vapid-public-key")
			.hasMessageNotContaining(malformed);
	}
}
