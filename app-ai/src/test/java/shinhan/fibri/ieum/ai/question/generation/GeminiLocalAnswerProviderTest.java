package shinhan.fibri.ieum.ai.question.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.errors.GenAiIOException;
import com.google.genai.types.GenerateContentConfig;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class GeminiLocalAnswerProviderTest {

	private static final Instant STARTED_AT = Instant.parse("2026-07-13T10:15:31Z");

	@Test
	void requestsStrictJsonSchemaWithSearchAndAllToolsAbsent() {
		CapturingGeminiClient client = new CapturingGeminiClient(new GeminiLocalAnswerClientResponse(
			validOutput(), 53, 17, "gemini-request-1"
		));
		GeminiLocalAnswerProvider provider = provider(client);

		LocalAnswerProviderResponse result = provider.generate(prompt());

		GeminiLocalAnswerRequest request = client.request;
		assertThat(request.model()).isEqualTo("gemini-3.1-flash-lite");
		assertThat(request.responseMimeType()).isEqualTo("application/json");
		assertThat(request.googleSearchGroundingEnabled()).isFalse();
		assertThat(request.maxOutputTokens()).isEqualTo(1024);
		assertThat(request.temperature()).isEqualTo(0.0f);
		assertThat(request.responseJsonSchema()).containsEntry("additionalProperties", false);
		assertThat(request.responseJsonSchema()).containsEntry("required", List.of("answer", "citations"));
		GenerateContentConfig config = GeminiGoogleGenAiLocalAnswerClient.config(request);
		assertThat(config.responseMimeType()).contains("application/json");
		assertThat(config.responseJsonSchema()).contains(request.responseJsonSchema());
		assertThat(config.tools()).isEmpty();
		assertThat(config.toolConfig()).isEmpty();
		assertThat(result.rawOutput()).isEqualTo(validOutput());
		assertThat(result.inputTokenCount()).isEqualTo(53);
		assertThat(result.outputTokenCount()).isEqualTo(17);
		assertThat(result.providerRequestId()).isEqualTo("gemini-request-1");
		assertThat(result.startedAt()).isEqualTo(STARTED_AT);
	}

	@Test
	void refusesAnyRequestThatWouldEnableGoogleSearch() {
		assertThatThrownBy(() -> new GeminiLocalAnswerRequest(
			"gemini-3.1-flash-lite",
			"system",
			"user",
			"application/json",
			Map.of("type", "object"),
			0.0f,
			1024,
			true
		)).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("Search");
	}

	@Test
	void mapsClientFailuresWithoutLeakingSdkDetails() {
		GeminiLocalAnswerProvider provider = provider(request -> {
			throw new GeminiLocalAnswerClientException(
				LocalAnswerProviderFailureCode.rate_limited,
				"raw quota detail"
			);
		});

		assertThatThrownBy(() -> provider.generate(prompt()))
			.isInstanceOfSatisfying(LocalAnswerProviderException.class, exception -> {
				assertThat(exception.failureCode()).isEqualTo(LocalAnswerProviderFailureCode.rate_limited);
				assertThat(exception).hasMessageNotContaining("raw quota detail");
			});
	}

	@ParameterizedTest
	@MethodSource("timeoutCauses")
	void classifiesGeminiIoTimeoutCauseChainsWithoutLeakingRawMessages(Throwable timeoutCause) {
		GenAiIOException sdkException = new GenAiIOException(
			"raw SDK message containing secret prompt",
			new IllegalStateException("transport wrapper", timeoutCause)
		);

		GeminiLocalAnswerClientException sanitized = GeminiGoogleGenAiLocalAnswerClient.failure(sdkException);

		assertThat(sanitized.failureCode()).isEqualTo(LocalAnswerProviderFailureCode.timeout);
		assertThat(sanitized).hasMessageNotContaining("raw SDK message");
		assertThat(sanitized).hasMessageNotContaining("secret prompt");
		assertThat(sanitized.getCause()).isNull();
	}

	@Test
	void stopsTraversingWhenGeminiIoCauseChainContainsACycle() {
		BoundedCyclicCause first = new BoundedCyclicCause("first");
		BoundedCyclicCause second = new BoundedCyclicCause("second");
		first.linkTo(second);
		second.linkTo(first);
		GenAiIOException sdkException = new GenAiIOException("raw SDK message", first);

		GeminiLocalAnswerClientException sanitized = GeminiGoogleGenAiLocalAnswerClient.failure(sdkException);

		assertThat(sanitized.failureCode()).isEqualTo(LocalAnswerProviderFailureCode.provider_unavailable);
		assertThat(sanitized.getCause()).isNull();
	}

	private GeminiLocalAnswerProvider provider(GeminiLocalAnswerClient client) {
		return new GeminiLocalAnswerProvider(
			client,
			new LocalAnswerPromptFactory(new ObjectMapper()),
			properties(),
			Clock.fixed(STARTED_AT, ZoneOffset.UTC)
		);
	}

	private LocalAnswerProperties properties() {
		return new LocalAnswerProperties(
			"amazon.nova-micro-v1:0",
			"gemini-3.1-flash-lite",
			"question-local-answer-v1",
			1024,
			Duration.ofSeconds(30)
		);
	}

	private LocalAnswerPrompt prompt() {
		return new LocalAnswerPrompt(
			"버스 이용",
			"앞문으로 타나요?",
			LocalAnswerRegion.empty(),
			List.of(new LocalAnswerEvidence(0, "버스 안내", "앞문 승차, 뒷문 하차", "government"))
		);
	}

	private static String validOutput() {
		return "{\"answer\":\"앞문으로 타세요.\",\"citations\":[{\"evidenceIndex\":0,\"startIndex\":0,\"endIndex\":9}]}";
	}

	private static java.util.stream.Stream<Throwable> timeoutCauses() {
		return java.util.stream.Stream.of(
			new SocketTimeoutException("raw socket timeout"),
			new InterruptedIOException("raw interrupted I/O"),
			new TimeoutException("raw future timeout")
		);
	}

	private static final class BoundedCyclicCause extends RuntimeException {

		private Throwable next;
		private int traversalCount;

		private BoundedCyclicCause(String message) {
			super(message);
		}

		private void linkTo(Throwable next) {
			this.next = next;
		}

		@Override
		public synchronized Throwable getCause() {
			if (++traversalCount > 1) {
				throw new AssertionError("cyclic cause was traversed more than once");
			}
			return next;
		}
	}

	private static final class CapturingGeminiClient implements GeminiLocalAnswerClient {

		private final GeminiLocalAnswerClientResponse response;
		private GeminiLocalAnswerRequest request;

		private CapturingGeminiClient(GeminiLocalAnswerClientResponse response) {
			this.response = response;
		}

		@Override
		public GeminiLocalAnswerClientResponse generate(GeminiLocalAnswerRequest request) {
			this.request = request;
			return response;
		}
	}
}
