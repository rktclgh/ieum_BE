package shinhan.fibri.ieum.main.notification.push;

import com.interaso.webpush.VapidKeys;
import com.interaso.webpush.WebPush;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;

public class InterasoWebPushProviderClient implements WebPushProviderClient {

	private final HttpClient httpClient;
	private final WebPushTransportProperties properties;
	private final VapidKeys vapidKeys;

	public InterasoWebPushProviderClient(
		HttpClient httpClient,
		WebPushTransportProperties properties,
		VapidKeys vapidKeys
	) {
		this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
		this.properties = Objects.requireNonNull(properties, "properties");
		this.vapidKeys = Objects.requireNonNull(vapidKeys, "vapidKeys");
	}

	@Override
	public int send(WebPushProviderRequest request) {
		Objects.requireNonNull(request, "request");
		try {
			byte[] p256dh = decodeKey(request.p256dh(), 65, true);
			byte[] auth = decodeKey(request.auth(), 16, false);
			WebPush webPush = new WebPush(properties.vapidSubject(), vapidKeys);
			byte[] encryptedBody = webPush.getBody(request.payload(), p256dh, auth);
			Map<String, String> headers = webPush.getHeaders(
				request.endpoint(),
				request.ttlSeconds(),
				request.topic(),
				request.urgency()
			);
			HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(URI.create(request.endpoint()))
				.timeout(properties.requestTimeout());
			headers.forEach(requestBuilder::header);
			HttpRequest httpRequest = requestBuilder
				.POST(HttpRequest.BodyPublishers.ofByteArray(encryptedBody))
				.build();
			HttpResponse<Void> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.discarding());
			return response.statusCode();
		}
		catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new WebPushProviderException("Web Push provider send was interrupted");
		}
		catch (IOException exception) {
			throw new WebPushProviderException("Web Push provider I/O failed");
		}
		catch (RuntimeException exception) {
			throw new WebPushProviderException("Web Push request preparation failed");
		}
	}

	private static byte[] decodeKey(String value, int expectedLength, boolean requireUncompressedPrefix) {
		byte[] decoded = Base64.getUrlDecoder().decode(value);
		if (decoded.length != expectedLength || (requireUncompressedPrefix && decoded[0] != 0x04)) {
			throw new IllegalArgumentException("Invalid Web Push subscription key");
		}
		return decoded;
	}
}
