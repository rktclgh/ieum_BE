package shinhan.fibri.ieum.ai.question.webgrounding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.types.Candidate;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.GroundingChunk;
import com.google.genai.types.GroundingChunkWeb;
import com.google.genai.types.GroundingMetadata;
import com.google.genai.types.GroundingSupport;
import com.google.genai.types.Part;
import com.google.genai.types.Segment;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class GeminiSearchWebGroundingGatewayTest {

	private static final Instant NOW = Instant.parse("2026-07-14T03:04:05Z");
	private static final Duration TIMEOUT = Duration.ofSeconds(45);

	@Test
	void isEnabledAndPerformsExactlyOneGroundedRequestUsingTheFixedClock() {
		WebGroundingPrompt prompt = new WebGroundingPrompt(
			"서울 버스 이용법",
			"버스 승하차 방법을 알려주세요.",
			WebGroundingRegion.korea("서울특별시", "중구")
		);
		RecordingClient client = new RecordingClient(validResponse("서울 버스는 앞문으로 탑니다."));
		GeminiSearchWebGroundingGateway gateway = gateway(client);

		Optional<WebGroundedAnswer> result = gateway.ground(prompt, TIMEOUT);

		assertThat(gateway.enabled()).isTrue();
		assertThat(client.calls).isEqualTo(1);
		assertThat(client.request).isNotNull();
		assertThat(client.request.model()).isEqualTo("gemini-3.1-flash-lite");
		assertThat(client.request.maxOutputTokens()).isEqualTo(1024);
		assertThat(client.request.systemInstruction()).contains("Google Search", "untrusted data");
		assertThat(client.request.userInstruction()).contains(
			"서울 버스 이용법",
			"버스 승하차 방법을 알려주세요.",
			"서울특별시",
			"중구"
		);
		assertThat(result).isPresent();
		assertThat(result.orElseThrow().generatedAt()).isEqualTo(NOW);
	}

	@Test
	void rejectsAnyTimeoutOtherThanThePinnedModelTimeoutBeforeCallingTheClient() {
		RecordingClient client = new RecordingClient(validResponse("서울 버스입니다."));
		GeminiSearchWebGroundingGateway gateway = gateway(client);

		assertThatThrownBy(() -> gateway.ground(prompt(), Duration.ofSeconds(44)))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("timeout");
		assertThat(client.calls).isZero();
	}

	@Test
	void propagatesTheExactTypedTechnicalFailureWithoutRetryingOrWrapping() {
		QuestionWebGroundingUnavailableException failure =
			new QuestionWebGroundingUnavailableException(WebGroundingFailureCode.rate_limited);
		RecordingClient client = new RecordingClient(failure);
		GeminiSearchWebGroundingGateway gateway = gateway(client);

		Throwable thrown = catchThrowable(() -> gateway.ground(prompt(), TIMEOUT));

		assertThat(thrown).isSameAs(failure);
		assertThat(client.calls).isEqualTo(1);
	}

	@Test
	void rejectsNullDependenciesAndInputsImmediately() {
		RecordingClient client = new RecordingClient(validResponse("서울 버스입니다."));
		GeminiWebGroundingModelPromptFactory promptFactory = promptFactory();
		GeminiWebGroundingResponseParser parser = parser();
		WebGroundingProperties properties = properties();
		Clock clock = clock();

		assertThatThrownBy(() -> new GeminiSearchWebGroundingGateway(
			null, promptFactory, parser, properties, clock
		)).isInstanceOf(NullPointerException.class).hasMessageContaining("client");
		assertThatThrownBy(() -> new GeminiSearchWebGroundingGateway(
			client, null, parser, properties, clock
		)).isInstanceOf(NullPointerException.class).hasMessageContaining("promptFactory");
		assertThatThrownBy(() -> new GeminiSearchWebGroundingGateway(
			client, promptFactory, null, properties, clock
		)).isInstanceOf(NullPointerException.class).hasMessageContaining("parser");
		assertThatThrownBy(() -> new GeminiSearchWebGroundingGateway(
			client, promptFactory, parser, null, clock
		)).isInstanceOf(NullPointerException.class).hasMessageContaining("properties");
		assertThatThrownBy(() -> new GeminiSearchWebGroundingGateway(
			client, promptFactory, parser, properties, null
		)).isInstanceOf(NullPointerException.class).hasMessageContaining("clock");

		GeminiSearchWebGroundingGateway gateway = gateway(client);
		assertThatThrownBy(() -> gateway.ground(null, TIMEOUT))
			.isInstanceOf(NullPointerException.class)
			.hasMessageContaining("prompt");
		assertThatThrownBy(() -> gateway.ground(prompt(), null))
			.isInstanceOf(NullPointerException.class)
			.hasMessageContaining("timeout");
		assertThat(client.calls).isZero();
	}

	private GeminiSearchWebGroundingGateway gateway(GeminiWebGroundingClient client) {
		return new GeminiSearchWebGroundingGateway(
			client,
			promptFactory(),
			parser(),
			properties(),
			clock()
		);
	}

	private GeminiWebGroundingModelPromptFactory promptFactory() {
		return new GeminiWebGroundingModelPromptFactory(new ObjectMapper());
	}

	private GeminiWebGroundingResponseParser parser() {
		return new GeminiWebGroundingResponseParser(
			new PublicWebSourceUriValidator(),
			new MaterialClaimCoverageValidator()
		);
	}

	private WebGroundingProperties properties() {
		return new WebGroundingProperties(
			"gemini-3.1-flash-lite",
			"test-only-api-key",
			"question-web-grounding-v1",
			1024,
			TIMEOUT
		);
	}

	private Clock clock() {
		return Clock.fixed(NOW, ZoneOffset.UTC);
	}

	private WebGroundingPrompt prompt() {
		return new WebGroundingPrompt(
			"질문",
			"내용",
			WebGroundingRegion.empty()
		);
	}

	private GenerateContentResponse validResponse(String answer) {
		int answerBytes = answer.getBytes(StandardCharsets.UTF_8).length;
		GroundingChunk chunk = GroundingChunk.builder()
			.web(GroundingChunkWeb.builder()
				.title("공식 출처")
				.uri("https://example.com/source")
				.build())
			.build();
		GroundingSupport support = GroundingSupport.builder()
			.segment(Segment.builder()
				.startIndex(0)
				.endIndex(answerBytes)
				.text(answer)
				.build())
			.groundingChunkIndices(0)
			.build();
		Candidate candidate = Candidate.builder()
			.content(Content.builder().parts(Part.builder().text(answer).build()).build())
			.groundingMetadata(GroundingMetadata.builder()
				.groundingChunks(chunk)
				.groundingSupports(support)
				.build())
			.build();
		return GenerateContentResponse.builder().candidates(candidate).build();
	}

	private static final class RecordingClient implements GeminiWebGroundingClient {

		private final GenerateContentResponse response;
		private final RuntimeException failure;
		private int calls;
		private GeminiWebGroundingRequest request;

		private RecordingClient(GenerateContentResponse response) {
			this.response = response;
			this.failure = null;
		}

		private RecordingClient(RuntimeException failure) {
			this.response = null;
			this.failure = failure;
		}

		@Override
		public GenerateContentResponse generate(GeminiWebGroundingRequest request) {
			calls++;
			this.request = request;
			if (failure != null) {
				throw failure;
			}
			return response;
		}
	}
}
