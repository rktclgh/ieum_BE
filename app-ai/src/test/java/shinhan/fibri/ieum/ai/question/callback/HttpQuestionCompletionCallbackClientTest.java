package shinhan.fibri.ieum.ai.question.callback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class HttpQuestionCompletionCallbackClientTest {

	private HttpServer server;

	@AfterEach
	void stopServer() {
		if (server != null) {
			server.stop(0);
		}
	}

	@Test
	void postsTheExactPathTokenAndAnswerBodyAndAcceptsEveryTwoHundredResponse() throws Exception {
		AtomicReference<String> path = new AtomicReference<>();
		AtomicReference<String> token = new AtomicReference<>();
		AtomicReference<String> contentType = new AtomicReference<>();
		AtomicReference<String> body = new AtomicReference<>();
		start(exchange -> {
			path.set(exchange.getRequestURI().getRawPath());
			token.set(exchange.getRequestHeaders().getFirst("X-IEUM-Internal-Token"));
			contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
			body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			respond(exchange, 200);
		});

		CallbackHttpResult result = client(Duration.ofSeconds(2)).deliver(42L, 123L);

		assertThat(result).isEqualTo(CallbackHttpResult.DELIVERED);
		assertThat(path.get()).isEqualTo("/api/v1/internal/ai/question-answer-jobs/42/completed");
		assertThat(token.get()).isEqualTo("shared-secret");
		assertThat(contentType.get()).startsWith("application/json");
		assertThat(body.get()).isEqualTo("{\"answerId\":123}");
	}

	@Test
	void returnsFailureOnceForNonTwoHundredWithoutAnImmediateRetry() throws Exception {
		AtomicInteger calls = new AtomicInteger();
		start(exchange -> {
			calls.incrementAndGet();
			respond(exchange, 503);
		});

		assertThat(client(Duration.ofSeconds(2)).deliver(42L, 123L))
			.isEqualTo(CallbackHttpResult.FAILED);
		assertThat(calls).hasValue(1);
	}

	@Test
	void exposesNotFoundForTheDeliveryServiceToRecheckDurableState() throws Exception {
		start(exchange -> respond(exchange, 404));

		assertThat(client(Duration.ofSeconds(2)).deliver(42L, 123L))
			.isEqualTo(CallbackHttpResult.NOT_FOUND);
	}

	@Test
	void neverFollowsRedirects() throws Exception {
		AtomicInteger redirectTargetCalls = new AtomicInteger();
		server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		server.createContext("/api/v1/internal/ai/question-answer-jobs/42/completed", exchange -> {
			exchange.getResponseHeaders().add("Location", "/redirect-target");
			respond(exchange, 302);
		});
		server.createContext("/redirect-target", exchange -> {
			redirectTargetCalls.incrementAndGet();
			respond(exchange, 204);
		});
		server.start();

		assertThat(client(Duration.ofSeconds(2)).deliver(42L, 123L))
			.isEqualTo(CallbackHttpResult.FAILED);
		assertThat(redirectTargetCalls).hasValue(0);
	}

	@Test
	void returnsFailureOnReadTimeoutWithoutRetrying() throws Exception {
		AtomicInteger calls = new AtomicInteger();
		start(exchange -> {
			calls.incrementAndGet();
			try {
				Thread.sleep(250);
			}
			catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
			}
			respond(exchange, 204);
		});

		assertThat(client(Duration.ofMillis(40)).deliver(42L, 123L))
			.isEqualTo(CallbackHttpResult.FAILED);
		assertThat(calls).hasValue(1);
	}

	@Test
	void rejectsAnHttpClientThatCouldFollowRedirects() throws Exception {
		start(exchange -> respond(exchange, 204));
		QuestionCompletionCallbackProperties properties = properties(Duration.ofSeconds(2));

		assertThatThrownBy(() -> new HttpQuestionCompletionCallbackClient(
			HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build(),
			properties
		))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("redirect");
	}

	private HttpQuestionCompletionCallbackClient client(Duration readTimeout) {
		QuestionCompletionCallbackProperties properties = properties(readTimeout);
		HttpClient httpClient = HttpClient.newBuilder()
			.connectTimeout(properties.connectTimeout())
			.followRedirects(HttpClient.Redirect.NEVER)
			.build();
		return new HttpQuestionCompletionCallbackClient(httpClient, properties);
	}

	private QuestionCompletionCallbackProperties properties(Duration readTimeout) {
		String origin = "http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort();
		return QuestionCompletionCallbackProperties.create(
			origin,
			origin,
			"shared-secret",
			Duration.ofSeconds(2),
			readTimeout
		);
	}

	private void start(ExchangeHandler handler) throws IOException {
		server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		server.createContext("/api/v1/internal/ai/question-answer-jobs/42/completed", exchange -> handler.handle(exchange));
		server.start();
	}

	private static void respond(HttpExchange exchange, int status) throws IOException {
		exchange.sendResponseHeaders(status, -1);
		exchange.close();
	}

	@FunctionalInterface
	private interface ExchangeHandler {
		void handle(HttpExchange exchange) throws IOException;
	}
}
