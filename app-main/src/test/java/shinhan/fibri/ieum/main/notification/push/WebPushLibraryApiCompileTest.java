package shinhan.fibri.ieum.main.notification.push;

import static org.assertj.core.api.Assertions.assertThat;

import com.interaso.webpush.VapidKeys;
import com.interaso.webpush.WebPush;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WebPushLibraryApiCompileTest {

	@Test
	void exposesTheJavaApiUsedByTheProviderAdapter() {
		assertThat(WebPush.Urgency.Normal).isNotNull();
	}

	@SuppressWarnings("unused")
	private static void compileContract(
		String publicKey,
		String privateKey,
		String subject,
		String endpoint,
		byte[] payload,
		byte[] p256dh,
		byte[] auth
	) {
		VapidKeys keys = VapidKeys.fromUncompressedBytes(publicKey, privateKey);
		WebPush webPush = new WebPush(subject, keys);
		byte[] body = webPush.getBody(payload, p256dh, auth);
		Map<String, String> headers = webPush.getHeaders(endpoint, 300, "chat-room", WebPush.Urgency.Normal);
		assertThat(body).isNotNull();
		assertThat(headers).isNotNull();
	}
}
