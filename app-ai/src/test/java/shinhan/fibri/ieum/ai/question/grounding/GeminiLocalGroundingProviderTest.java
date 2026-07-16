package shinhan.fibri.ieum.ai.question.grounding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import shinhan.fibri.ieum.ai.question.generation.LocalAnswerProperties;
import shinhan.fibri.ieum.ai.question.generation.LocalAnswerProviderFailureCode;

class GeminiLocalGroundingProviderTest {

	private static final Instant STARTED_AT = Instant.parse("2026-07-13T10:15:31Z");

	@Test
	void validationUsesExactSchemaWithSearchAndToolsDisabled() {
		CapturingClient client = new CapturingClient(new GeminiLocalGroundingProvider.ClientResponse(
			validationOutput(), 50, 11, "gemini-validation-1"
		));
		GeminiLocalGroundingProvider provider = provider(client);

		GroundingProviderResponse result = provider.validate(modelPrompt());

		GeminiLocalGroundingProvider.ClientRequest request = client.request;
		assertThat(request.model()).isEqualTo("gemini-3.1-flash-lite");
		assertThat(request.responseMimeType()).isEqualTo("application/json");
		assertThat(request.temperature()).isEqualTo(0.0f);
		assertThat(request.maxOutputTokens()).isEqualTo(512);
		assertThat(request.googleSearchGroundingEnabled()).isFalse();
		assertThat(request.responseJsonSchema()).isEqualTo(validationSchema());
		GenerateContentConfig config = GeminiLocalGroundingProvider.GoogleClient.config(request);
		assertThat(config.tools()).isEmpty();
		assertThat(config.toolConfig()).isEmpty();
		assertThat(result.rawOutput()).isEqualTo(validationOutput());
		assertThat(result.inputTokenCount()).isEqualTo(50);
		assertThat(result.outputTokenCount()).isEqualTo(11);
		assertThat(result.providerRequestId()).isEqualTo("gemini-validation-1");
		assertThat(result.startedAt()).isEqualTo(STARTED_AT);
	}

	@Test
	void repairUsesExactLocalAnswerSchemaAndItsOwnTokenBudget() {
		CapturingClient client = new CapturingClient(new GeminiLocalGroundingProvider.ClientResponse(
			repairOutput(), 70, 18, "gemini-repair-1"
		));

		GroundingProviderResponse result = provider(client).repair(modelPrompt());

		GeminiLocalGroundingProvider.ClientRequest request = client.request;
		assertThat(request.maxOutputTokens()).isEqualTo(1024);
		assertThat(request.responseJsonSchema()).isEqualTo(repairSchema());
		assertThat(request.googleSearchGroundingEnabled()).isFalse();
		GenerateContentConfig config = GeminiLocalGroundingProvider.GoogleClient.config(request);
		assertThat(config.tools()).isEmpty();
		assertThat(config.toolConfig()).isEmpty();
		assertThat(result.rawOutput()).isEqualTo(repairOutput());
	}

	@Test
	void clientRequestRejectsAnySearchOrToolEnablingShape() {
		assertThatThrownBy(() -> new GeminiLocalGroundingProvider.ClientRequest(
			"gemini-3.1-flash-lite",
			"system",
			"user",
			"application/json",
			Map.of("type", "object"),
			0.0f,
			512,
			true
		)).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("Search");
	}

	@Test
	void mapsClientFailuresWithoutLeakingSdkDetails() {
		GeminiLocalGroundingProvider provider = provider(request -> {
			throw new GeminiLocalGroundingProvider.ClientException(LocalAnswerProviderFailureCode.rate_limited);
		});

		assertThatThrownBy(() -> provider.validate(modelPrompt()))
			.isInstanceOfSatisfying(GroundingProviderException.class, exception -> {
				assertThat(exception.failureCode()).isEqualTo(LocalAnswerProviderFailureCode.rate_limited);
				assertThat(exception)
					.hasMessageNotContaining("raw")
					.hasMessageNotContaining("quota")
					.hasMessageNotContaining("prompt");
				assertThat(exception.getCause()).isNull();
			});
	}

	@ParameterizedTest
	@MethodSource("timeoutCauses")
	void classifiesIoTimeoutCauseChainsWithoutLeakingRawMessages(Throwable timeoutCause) {
		GenAiIOException sdkException = new GenAiIOException(
			"raw SDK message containing secret prompt",
			new IllegalStateException("transport wrapper", timeoutCause)
		);

		GeminiLocalGroundingProvider.ClientException sanitized =
			GeminiLocalGroundingProvider.GoogleClient.failure(sdkException);

		assertThat(sanitized.failureCode()).isEqualTo(LocalAnswerProviderFailureCode.timeout);
		assertThat(sanitized)
			.hasMessageNotContaining("raw")
			.hasMessageNotContaining("secret")
			.hasMessageNotContaining("prompt");
		assertThat(sanitized.getCause()).isNull();
	}

	@Test
	void stopsTraversingWhenIoCauseChainContainsACycle() {
		BoundedCyclicCause first = new BoundedCyclicCause("first");
		BoundedCyclicCause second = new BoundedCyclicCause("second");
		first.linkTo(second);
		second.linkTo(first);
		GenAiIOException sdkException = new GenAiIOException("raw SDK message", first);

		GeminiLocalGroundingProvider.ClientException sanitized =
			GeminiLocalGroundingProvider.GoogleClient.failure(sdkException);

		assertThat(sanitized.failureCode()).isEqualTo(LocalAnswerProviderFailureCode.provider_unavailable);
		assertThat(sanitized.getCause()).isNull();
	}

	private Map<String, Object> validationSchema() {
		return Map.of(
			"type", "object",
			"additionalProperties", false,
			"required", List.of("supported", "score", "unsupportedClaims"),
			"properties", Map.of(
				"supported", Map.of("type", "boolean"),
				"score", Map.of("type", "number", "minimum", 0, "maximum", 1),
				"unsupportedClaims", Map.of(
					"type", "array",
					"minItems", 0,
					"maxItems", 8,
					"items", Map.of("type", "string", "minLength", 1, "maxLength", 500)
				)
			)
		);
	}

	private Map<String, Object> repairSchema() {
		Map<String, Object> integer = Map.of("type", "integer", "minimum", 0);
		Map<String, Object> citation = Map.of(
			"type", "object",
			"additionalProperties", false,
			"required", List.of("evidenceIndex", "startIndex", "endIndex"),
			"properties", Map.of(
				"evidenceIndex", integer,
				"startIndex", integer,
				"endIndex", Map.of("type", "integer", "minimum", 1)
			)
		);
		return Map.of(
			"type", "object",
			"additionalProperties", false,
			"required", List.of("answer", "citations"),
			"properties", Map.of(
				"answer", Map.of("type", "string", "minLength", 1),
				"citations", Map.of(
					"type", "array",
					"minItems", 1,
					"maxItems", 8,
					"items", citation
				)
			)
		);
	}

	private GeminiLocalGroundingProvider provider(GeminiLocalGroundingProvider.Client client) {
		return new GeminiLocalGroundingProvider(
			client,
			answerProperties(),
			properties(),
			Clock.fixed(STARTED_AT, ZoneOffset.UTC)
		);
	}

	private LocalAnswerProperties answerProperties() {
		return new LocalAnswerProperties(
			"amazon.nova-micro-v1:0",
			"gemini-3.1-flash-lite",
			"question-local-answer-v1",
			1024,
			Duration.ofSeconds(30)
		);
	}

	private LocalGroundingProperties properties() {
		return new LocalGroundingProperties(
			"question-grounding-validation-v1",
			"question-grounding-repair-v1",
			512,
			1024,
			Duration.ofSeconds(30)
		);
	}

	private GroundingModelPrompt modelPrompt() {
		return new GroundingModelPrompt("system instruction", "sanitized user payload");
	}

	private static String validationOutput() {
		return "{\"supported\":true,\"score\":0.93,\"unsupportedClaims\":[]}";
	}

	private static String repairOutput() {
		return "{\"answer\":\"앞문으로 승차하세요.\",\"citations\":[{\"evidenceIndex\":0,\"startIndex\":0,\"endIndex\":10}]}";
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

	private static final class CapturingClient implements GeminiLocalGroundingProvider.Client {

		private final GeminiLocalGroundingProvider.ClientResponse response;
		private GeminiLocalGroundingProvider.ClientRequest request;

		private CapturingClient(GeminiLocalGroundingProvider.ClientResponse response) {
			this.response = response;
		}

		@Override
		public GeminiLocalGroundingProvider.ClientResponse generate(
			GeminiLocalGroundingProvider.ClientRequest request
		) {
			this.request = request;
			return response;
		}
	}
}
