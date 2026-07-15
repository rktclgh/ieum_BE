package shinhan.fibri.ieum.main.notification.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.interaso.webpush.VapidKeys;
import com.interaso.webpush.WebPush;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class InterasoWebPushProviderClientTest {

	private static final String ENDPOINT = "https://push.example.test/message/secret-endpoint";
	private static final byte[] PAYLOAD = "payload-secret".getBytes(StandardCharsets.UTF_8);
	private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

	private HttpClient httpClient;
	private InterasoWebPushProviderClient client;

	@BeforeEach
	void setUp() {
		WebPushTestKeys.RawVapidKeys keys = WebPushTestKeys.generateVapidKeys();
		WebPushTransportProperties properties = new WebPushTransportProperties(
			keys.privateKey(),
			"mailto:ops@example.com",
			Duration.ofSeconds(2),
			REQUEST_TIMEOUT
		);
		VapidKeys vapidKeys = properties.createVapidKeys(keys.publicKey());
		httpClient = mock(HttpClient.class);
		client = new InterasoWebPushProviderClient(
			httpClient,
			properties,
			vapidKeys,
			new WebPushEndpointPolicy(Set.of("push.example.test"))
		);
	}

	@AfterEach
	void clearInterruptFlag() {
		Thread.interrupted();
	}

	@Test
	@SuppressWarnings({"rawtypes", "unchecked"})
	void sendsEncryptedPostWithConfiguredTimeoutAndWebPushHeaders() throws Exception {
		HttpResponse<Void> response = mock(HttpResponse.class);
		doReturn(202).when(response).statusCode();
		doReturn(response).when(httpClient).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));

		int status = client.send(validRequest());

		ArgumentCaptor<HttpRequest> requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
		verify(httpClient).send(requestCaptor.capture(), any(HttpResponse.BodyHandler.class));
		HttpRequest request = requestCaptor.getValue();
		assertThat(status).isEqualTo(202);
		assertThat(request.method()).isEqualTo("POST");
		assertThat(request.uri().toString()).isEqualTo(ENDPOINT);
		assertThat(request.timeout()).contains(REQUEST_TIMEOUT);
		assertThat(request.headers().firstValue("Authorization")).isPresent();
		assertThat(request.headers().firstValue("Content-Encoding")).contains("aes128gcm");
		assertThat(request.headers().firstValue("Content-Type")).contains("application/octet-stream");
		assertThat(request.headers().firstValue("TTL")).contains("300");
		assertThat(request.headers().firstValue("Topic")).contains("chat-room");
		assertThat(request.headers().firstValue("Urgency")).contains("normal");
		assertThat(readBody(request)).isNotEmpty().isNotEqualTo(PAYLOAD);
	}

	@Test
	@SuppressWarnings({"rawtypes", "unchecked"})
	void sanitizesIoFailureWithoutRetainingSecretCause() throws Exception {
		doThrow(new IOException("failed " + ENDPOINT + " payload-secret"))
			.when(httpClient).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));

		assertThatThrownBy(() -> client.send(validRequest()))
			.isInstanceOf(WebPushProviderException.class)
			.hasMessageNotContaining(ENDPOINT)
			.hasMessageNotContaining("payload-secret")
			.hasCause(null);
	}

	@Test
	@SuppressWarnings({"rawtypes", "unchecked"})
	void restoresInterruptFlagAndSanitizesInterruptedFailure() throws Exception {
		doThrow(new InterruptedException("interrupted at " + ENDPOINT))
			.when(httpClient).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));

		assertThatThrownBy(() -> client.send(validRequest()))
			.isInstanceOf(WebPushProviderException.class)
			.hasMessageNotContaining(ENDPOINT)
			.hasCause(null);
		assertThat(Thread.currentThread().isInterrupted()).isTrue();
	}

	@Test
	void sanitizesMalformedSubscriptionKeyFailureBeforeNetworkCall() {
		WebPushProviderRequest request = new WebPushProviderRequest(
			PAYLOAD,
			ENDPOINT,
			"not-base64url+secret",
			WebPushTestKeys.authSecret(),
			300,
			null,
			WebPush.Urgency.Normal
		);

		assertThatThrownBy(() -> client.send(request))
			.isInstanceOf(WebPushProviderException.class)
			.hasMessageNotContaining("not-base64url+secret")
			.hasMessageNotContaining(ENDPOINT)
			.hasCause(null);
		verifyNoInteractions(httpClient);
	}

	@Test
	void blocksDisallowedEndpointBeforeEncryptionOrNetworkCall() {
		String disallowedEndpoint = "https://push.example.test.evil.example/message/secret-endpoint";

		assertThatThrownBy(() -> client.send(validRequest(disallowedEndpoint)))
			.isInstanceOf(WebPushProviderException.class)
			.hasMessageNotContaining(disallowedEndpoint)
			.hasMessageNotContaining("secret-endpoint")
			.hasCause(null);
		verifyNoInteractions(httpClient);
	}

	@Test
	void blocksOversizedEndpointBeforeEncryptionOrNetworkCall() {
		String oversizedEndpoint = "https://push.example.test/" + "a".repeat(2_049);

		assertThatThrownBy(() -> client.send(validRequest(oversizedEndpoint)))
			.isInstanceOf(WebPushProviderException.class)
			.hasMessageNotContaining(oversizedEndpoint)
			.hasCause(null);
		verifyNoInteractions(httpClient);
	}

	private static WebPushProviderRequest validRequest() {
		return validRequest(ENDPOINT);
	}

	private static WebPushProviderRequest validRequest(String endpoint) {
		return new WebPushProviderRequest(
			PAYLOAD,
			endpoint,
			WebPushTestKeys.generateSubscriptionP256dh(),
			WebPushTestKeys.authSecret(),
			300,
			"chat-room",
			WebPush.Urgency.Normal
		);
	}

	private static byte[] readBody(HttpRequest request) {
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		CompletableFuture<byte[]> completed = new CompletableFuture<>();
		request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<>() {
			@Override
			public void onSubscribe(Flow.Subscription subscription) {
				subscription.request(Long.MAX_VALUE);
			}

			@Override
			public void onNext(java.nio.ByteBuffer item) {
				byte[] chunk = new byte[item.remaining()];
				item.get(chunk);
				output.writeBytes(chunk);
			}

			@Override
			public void onError(Throwable throwable) {
				completed.completeExceptionally(throwable);
			}

			@Override
			public void onComplete() {
				completed.complete(output.toByteArray());
			}
		});
		return completed.join();
	}
}
