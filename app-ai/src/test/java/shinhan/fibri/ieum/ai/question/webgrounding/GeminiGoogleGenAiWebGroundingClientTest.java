package shinhan.fibri.ieum.ai.question.webgrounding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.genai.Client;
import com.google.genai.errors.ApiException;
import com.google.genai.errors.GenAiIOException;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.Tool;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class GeminiGoogleGenAiWebGroundingClientTest {

	@Test
	void configuresPlainTextGenerationWithExactlyOneGoogleSearchTool() {
		GeminiWebGroundingRequest request = request();

		GenerateContentConfig config = GeminiGoogleGenAiWebGroundingClient.config(request);

		assertThat(config.systemInstruction()).get().extracting(content -> content.text())
			.isEqualTo("system instruction");
		assertThat(config.temperature()).contains(0.0f);
		assertThat(config.candidateCount()).contains(1);
		assertThat(config.maxOutputTokens()).contains(1536);
		assertThat(config.responseMimeType()).contains("text/plain");
		assertThat(config.responseSchema()).isEmpty();
		assertThat(config.responseJsonSchema()).isEmpty();
		assertThat(config.toolConfig()).isEmpty();

		assertThat(config.tools()).isPresent();
		assertThat(config.tools().orElseThrow()).singleElement().satisfies(this::assertGoogleSearchOnly);
	}

	@Test
	void generatePerformsExactlyOneSdkRequestAndReturnsItsResponse() throws Exception {
		AtomicInteger requestCount = new AtomicInteger();
		AtomicReference<String> requestBody = new AtomicReference<>();
		HttpServer server = HttpServer.create(
			new InetSocketAddress("127.0.0.1", 0),
			0
		);
		server.createContext("/", exchange -> {
			requestCount.incrementAndGet();
			requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			byte[] response = ("{\"candidates\":[{\"content\":{\"role\":\"model\","
				+ "\"parts\":[{\"text\":\"grounded answer\"}]},\"finishReason\":\"STOP\"}]}")
				.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().set("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, response.length);
			exchange.getResponseBody().write(response);
			exchange.close();
		});
		server.start();

		try (Client client = localClient(server)) {
			GeminiWebGroundingClient adapter = new GeminiGoogleGenAiWebGroundingClient(client);

			GenerateContentResponse response = adapter.generate(request());

			assertThat(response.text()).isEqualTo("grounded answer");
			assertThat(requestCount).hasValue(1);
			assertThat(requestBody.get()).contains(
				"system instruction",
				"user instruction",
				"googleSearch",
				"text/plain"
			);
		}
		finally {
			server.stop(0);
		}
	}

	@ParameterizedTest
	@MethodSource("apiStatusMappings")
	void mapsApiStatusCodesWithoutUsingProviderMessages(
		int statusCode,
		WebGroundingFailureCode expected
	) {
		ApiException exception = new ApiException(
			statusCode,
			"RAW_PROVIDER_STATUS",
			"raw provider message containing an API key and request payload"
		);

		assertThat(GeminiGoogleGenAiWebGroundingClient.map(exception)).isEqualTo(expected);
	}

	@ParameterizedTest
	@MethodSource("timeoutCauses")
	void mapsNestedIoTimeoutCausesToSanitizedTimeoutFailures(Throwable timeoutCause) {
		GenAiIOException sdkException = new GenAiIOException(
			"raw SDK message containing an API key and request payload",
			new IllegalStateException("transport wrapper", timeoutCause)
		);

		QuestionWebGroundingUnavailableException failure =
			GeminiGoogleGenAiWebGroundingClient.failure(sdkException);

		assertThat(failure.failureCode()).isEqualTo(WebGroundingFailureCode.timeout);
		assertThat(failure)
			.hasMessage("Question web grounding is unavailable")
			.hasMessageNotContaining("raw")
			.hasMessageNotContaining("API key")
			.hasMessageNotContaining("payload")
			.hasNoCause();
	}

	@Test
	void mapsGeneralIoFailuresToSanitizedProviderUnavailableFailures() {
		GenAiIOException sdkException = new GenAiIOException(
			"raw SDK message",
			new IOException("raw I/O detail")
		);

		QuestionWebGroundingUnavailableException failure =
			GeminiGoogleGenAiWebGroundingClient.failure(sdkException);

		assertThat(failure.failureCode()).isEqualTo(WebGroundingFailureCode.provider_unavailable);
		assertThat(failure)
			.hasMessage("Question web grounding is unavailable")
			.hasMessageNotContaining("raw")
			.hasMessageNotContaining("I/O")
			.hasNoCause();
	}

	@Test
	void traversesEachCauseAtMostOnceWhenTheIoCauseChainCycles() {
		BoundedCyclicCause first = new BoundedCyclicCause("first");
		BoundedCyclicCause second = new BoundedCyclicCause("second");
		first.linkTo(second);
		second.linkTo(first);
		GenAiIOException sdkException = new GenAiIOException("raw SDK message", first);

		QuestionWebGroundingUnavailableException failure =
			GeminiGoogleGenAiWebGroundingClient.failure(sdkException);

		assertThat(failure.failureCode()).isEqualTo(WebGroundingFailureCode.provider_unavailable);
		assertThat(failure).hasNoCause();
		assertThat(first.causeReads()).isEqualTo(1);
		assertThat(second.causeReads()).isEqualTo(1);
	}

	@Test
	void rejectsNullClientAndRequestImmediately() {
		assertThatThrownBy(() -> new GeminiGoogleGenAiWebGroundingClient(null))
			.isInstanceOf(NullPointerException.class)
			.hasMessageContaining("client");

		try (Client client = Client.builder().apiKey("test-api-key").build()) {
			GeminiWebGroundingClient adapter = new GeminiGoogleGenAiWebGroundingClient(client);

			assertThatThrownBy(() -> adapter.generate(null))
				.isInstanceOf(NullPointerException.class)
				.hasMessageContaining("request");
		}
	}

	private void assertGoogleSearchOnly(Tool tool) {
		assertThat(tool.googleSearch()).isPresent();
		assertThat(tool.retrieval()).isEmpty();
		assertThat(tool.functions()).isEmpty();
		assertThat(tool.computerUse()).isEmpty();
		assertThat(tool.fileSearch()).isEmpty();
		assertThat(tool.googleMaps()).isEmpty();
		assertThat(tool.codeExecution()).isEmpty();
		assertThat(tool.enterpriseWebSearch()).isEmpty();
		assertThat(tool.functionDeclarations()).isEmpty();
		assertThat(tool.googleSearchRetrieval()).isEmpty();
		assertThat(tool.parallelAiSearch()).isEmpty();
		assertThat(tool.urlContext()).isEmpty();
		assertThat(tool.mcpServers()).isEmpty();
	}

	private Client localClient(HttpServer server) {
		String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
		return Client.builder()
			.apiKey("test-api-key")
			.httpOptions(HttpOptions.builder().baseUrl(baseUrl).apiVersion("v1beta").build())
			.build();
	}

	private GeminiWebGroundingRequest request() {
		return new GeminiWebGroundingRequest(
			"gemini-3.1-flash-lite",
			"system instruction",
			"user instruction",
			1536
		);
	}

	private static Stream<Arguments> apiStatusMappings() {
		return Stream.of(
			Arguments.of(408, WebGroundingFailureCode.timeout),
			Arguments.of(504, WebGroundingFailureCode.timeout),
			Arguments.of(429, WebGroundingFailureCode.rate_limited),
			Arguments.of(400, WebGroundingFailureCode.permanent_configuration),
			Arguments.of(401, WebGroundingFailureCode.permanent_configuration),
			Arguments.of(499, WebGroundingFailureCode.permanent_configuration),
			Arguments.of(500, WebGroundingFailureCode.provider_unavailable),
			Arguments.of(503, WebGroundingFailureCode.provider_unavailable),
			Arguments.of(599, WebGroundingFailureCode.provider_unavailable),
			Arguments.of(0, WebGroundingFailureCode.provider_unavailable),
			Arguments.of(399, WebGroundingFailureCode.provider_unavailable),
			Arguments.of(600, WebGroundingFailureCode.provider_unavailable)
		);
	}

	private static Stream<Throwable> timeoutCauses() {
		return Stream.of(
			new SocketTimeoutException("raw socket timeout"),
			new InterruptedIOException("raw interrupted I/O"),
			new TimeoutException("raw future timeout")
		);
	}

	private static final class BoundedCyclicCause extends RuntimeException {

		private Throwable next;
		private int causeReads;

		private BoundedCyclicCause(String message) {
			super(message);
		}

		private void linkTo(Throwable next) {
			this.next = next;
		}

		private int causeReads() {
			return causeReads;
		}

		@Override
		public synchronized Throwable getCause() {
			if (++causeReads > 1) {
				throw new AssertionError("cause cycle traversal visited a node more than once");
			}
			return next;
		}
	}
}
