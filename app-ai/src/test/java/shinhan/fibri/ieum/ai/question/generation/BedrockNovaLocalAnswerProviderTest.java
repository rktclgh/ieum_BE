package shinhan.fibri.ieum.ai.question.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.springframework.ai.bedrock.converse.BedrockChatOptions;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

class BedrockNovaLocalAnswerProviderTest {

	private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
	private static final Instant STARTED_AT = Instant.parse("2026-07-13T10:15:30Z");

	@Test
	void usesRequestSpecificNovaMicroOptionsAndOnlyTheSanitizedModelPrompt() throws Exception {
		CapturingChatModel chatModel = new CapturingChatModel(prompt -> response(validOutput()));
		BedrockNovaLocalAnswerProvider provider = provider(chatModel);

		LocalAnswerProviderResponse result = provider.generate(prompt());

		Prompt request = chatModel.prompt;
		assertThat(request.getInstructions()).hasSize(2);
		List<Message> messages = request.getInstructions();
		assertThat(messages.get(0)).isInstanceOf(SystemMessage.class);
		assertThat(messages.get(1)).isInstanceOf(UserMessage.class);
		JsonNode payload = OBJECT_MAPPER.readTree(messages.get(1).getText());
		assertThat(fieldNames(payload)).containsExactlyInAnyOrder("question", "coarseRegion", "evidence");
		assertThat(messages.get(1).getText()).doesNotContain(
			"sourceId", "chunkId", "userId", "latitude", "longitude", "rawAddress", "detailAddress", "label"
		);
		assertThat(request.getOptions()).isInstanceOf(BedrockChatOptions.class);
		BedrockChatOptions options = (BedrockChatOptions) request.getOptions();
		assertThat(options.getModel()).isEqualTo("amazon.nova-micro-v1:0");
		assertThat(options.getTemperature()).isEqualTo(0.0d);
		assertThat(options.getMaxTokens()).isEqualTo(1024);
		assertThat(provider.provider()).isEqualTo("bedrock");
		assertThat(provider.model()).isEqualTo("amazon.nova-micro-v1:0");
		assertThat(result.rawOutput()).isEqualTo(validOutput());
		assertThat(result.startedAt()).isEqualTo(STARTED_AT);
	}

	@Test
	void extractsNullableProviderMetadataWithoutInventingValues() {
		ChatResponseMetadata metadata = ChatResponseMetadata.builder()
			.id("bedrock-request-1")
			.usage(new DefaultUsage(77, 21))
			.build();
		BedrockNovaLocalAnswerProvider provider = provider(new CapturingChatModel(prompt ->
			new ChatResponse(List.of(new Generation(new AssistantMessage(validOutput()))), metadata)
		));

		LocalAnswerProviderResponse result = provider.generate(prompt());

		assertThat(result.inputTokenCount()).isEqualTo(77);
		assertThat(result.outputTokenCount()).isEqualTo(21);
		assertThat(result.providerRequestId()).isEqualTo("bedrock-request-1");
	}

	@Test
	void mapsTimeoutsAndUnexpectedRuntimeFailuresToFiniteSanitizedCodes() {
		BedrockNovaLocalAnswerProvider timeout = provider(new CapturingChatModel(prompt -> {
			throw new IllegalStateException(new TimeoutException("raw timeout detail"));
		}));
		BedrockNovaLocalAnswerProvider unavailable = provider(new CapturingChatModel(prompt -> {
			throw new IllegalStateException("raw provider secret");
		}));

		assertThatThrownBy(() -> timeout.generate(prompt()))
			.isInstanceOfSatisfying(LocalAnswerProviderException.class, exception -> {
				assertThat(exception.failureCode()).isEqualTo(LocalAnswerProviderFailureCode.timeout);
				assertThat(exception).hasMessageNotContaining("raw timeout detail");
			});
		assertThatThrownBy(() -> unavailable.generate(prompt()))
			.isInstanceOfSatisfying(LocalAnswerProviderException.class, exception -> {
				assertThat(exception.failureCode()).isEqualTo(LocalAnswerProviderFailureCode.provider_unavailable);
				assertThat(exception).hasMessageNotContaining("raw provider secret");
			});
	}

	@Test
	void cyclicCauseChainTerminatesWithUnavailableCode() {
		BedrockNovaLocalAnswerProvider provider = provider(new CapturingChatModel(prompt -> {
			throw new FailOnRepeatedCauseAccessException();
		}));

		assertThatThrownBy(() -> provider.generate(prompt()))
			.isInstanceOfSatisfying(LocalAnswerProviderException.class, exception ->
				assertThat(exception.failureCode())
					.isEqualTo(LocalAnswerProviderFailureCode.provider_unavailable)
			);
	}

	private BedrockNovaLocalAnswerProvider provider(ChatModel chatModel) {
		LocalAnswerProperties properties = properties();
		return new BedrockNovaLocalAnswerProvider(
			chatModel,
			new LocalAnswerPromptFactory(OBJECT_MAPPER),
			properties,
			Clock.fixed(STARTED_AT, ZoneOffset.UTC)
		);
	}

	private LocalAnswerProperties properties() {
		return new LocalAnswerProperties(
			"amazon.nova-micro-v1:0",
			"gemini-3.1-flash-lite",
			"test-api-key",
			"question-local-answer-v1",
			1024,
			Duration.ofSeconds(30)
		);
	}

	private LocalAnswerPrompt prompt() {
		return new LocalAnswerPrompt(
			"버스 이용",
			"앞문으로 타나요?",
			LocalAnswerRegion.korea("서울특별시", "중구", null),
			List.of(new LocalAnswerEvidence(0, "버스 안내", "앞문 승차, 뒷문 하차", "government"))
		);
	}

	private static ChatResponse response(String output) {
		return new ChatResponse(List.of(new Generation(new AssistantMessage(output))));
	}

	private static String validOutput() {
		return "{\"answer\":\"앞문으로 타세요.\",\"citations\":[{\"evidenceIndex\":0,\"startIndex\":0,\"endIndex\":9}]}";
	}

	private static List<String> fieldNames(JsonNode node) {
		List<String> fields = new ArrayList<>();
		node.fieldNames().forEachRemaining(fields::add);
		return fields;
	}

	private static final class CapturingChatModel implements ChatModel {

		private final Function<Prompt, ChatResponse> function;
		private Prompt prompt;

		private CapturingChatModel(Function<Prompt, ChatResponse> function) {
			this.function = function;
		}

		@Override
		public ChatResponse call(Prompt prompt) {
			this.prompt = prompt;
			return function.apply(prompt);
		}
	}

	private static final class FailOnRepeatedCauseAccessException extends RuntimeException {

		private boolean causeAccessed;

		@Override
		public synchronized Throwable getCause() {
			if (causeAccessed) {
				throw new AssertionError("cyclic cause chain was traversed repeatedly");
			}
			causeAccessed = true;
			return this;
		}
	}
}
