package shinhan.fibri.ieum.ai.question.grounding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
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
import shinhan.fibri.ieum.ai.question.generation.LocalAnswerProperties;
import shinhan.fibri.ieum.ai.question.generation.LocalAnswerProviderFailureCode;

class BedrockNovaLocalGroundingProviderTest {

	private static final Instant STARTED_AT = Instant.parse("2026-07-13T10:15:30Z");

	@Test
	void validationUsesRequestSpecificNovaMicroOptions() {
		CapturingChatModel chatModel = new CapturingChatModel(prompt -> response(validationOutput()));
		BedrockNovaLocalGroundingProvider provider = provider(chatModel, 512, 1024);

		GroundingProviderResponse result = provider.validate(modelPrompt());

		assertPrompt(chatModel.prompt, 512);
		assertThat(provider.provider()).isEqualTo("bedrock");
		assertThat(provider.model()).isEqualTo("amazon.nova-micro-v1:0");
		assertThat(result.rawOutput()).isEqualTo(validationOutput());
		assertThat(result.startedAt()).isEqualTo(STARTED_AT);
	}

	@Test
	void repairUsesItsOwnTokenBudgetAndReturnsNullableMetadata() {
		ChatResponseMetadata metadata = ChatResponseMetadata.builder()
			.id("bedrock-grounding-1")
			.usage(new DefaultUsage(80, 22))
			.build();
		CapturingChatModel chatModel = new CapturingChatModel(prompt -> new ChatResponse(
			List.of(new Generation(new AssistantMessage(repairOutput()))),
			metadata
		));

		GroundingProviderResponse result = provider(chatModel, 512, 1024).repair(modelPrompt());

		assertPrompt(chatModel.prompt, 1024);
		assertThat(result.inputTokenCount()).isEqualTo(80);
		assertThat(result.outputTokenCount()).isEqualTo(22);
		assertThat(result.providerRequestId()).isEqualTo("bedrock-grounding-1");
	}

	@Test
	void mapsRuntimeFailuresToFiniteCodesWithoutRawMessageOrCause() {
		BedrockNovaLocalGroundingProvider timeout = provider(new CapturingChatModel(prompt -> {
			throw new IllegalStateException("secret wrapper", new TimeoutException("raw timeout prompt"));
		}), 512, 1024);
		BedrockNovaLocalGroundingProvider unavailable = provider(new CapturingChatModel(prompt -> {
			throw new IllegalStateException("raw provider secret");
		}), 512, 1024);

		assertThatThrownBy(() -> timeout.validate(modelPrompt()))
			.isInstanceOfSatisfying(GroundingProviderException.class, exception -> {
				assertThat(exception.failureCode()).isEqualTo(LocalAnswerProviderFailureCode.timeout);
				assertThat(exception)
					.hasMessageNotContaining("secret")
					.hasMessageNotContaining("raw")
					.hasMessageNotContaining("prompt");
				assertThat(exception.getCause()).isNull();
			});
		assertThatThrownBy(() -> unavailable.repair(modelPrompt()))
			.isInstanceOfSatisfying(GroundingProviderException.class, exception -> {
				assertThat(exception.failureCode())
					.isEqualTo(LocalAnswerProviderFailureCode.provider_unavailable);
				assertThat(exception)
					.hasMessageNotContaining("secret")
					.hasMessageNotContaining("raw");
				assertThat(exception.getCause()).isNull();
			});
	}

	@Test
	void cyclicCauseChainTerminatesWithUnavailableCode() {
		BedrockNovaLocalGroundingProvider provider = provider(new CapturingChatModel(prompt -> {
			throw new FailOnRepeatedCauseAccessException();
		}), 512, 1024);

		assertThatThrownBy(() -> provider.validate(modelPrompt()))
			.isInstanceOfSatisfying(GroundingProviderException.class, exception ->
				assertThat(exception.failureCode())
					.isEqualTo(LocalAnswerProviderFailureCode.provider_unavailable)
			);
	}

	private void assertPrompt(Prompt prompt, int expectedMaxTokens) {
		assertThat(prompt.getInstructions()).hasSize(2);
		List<Message> messages = prompt.getInstructions();
		assertThat(messages.get(0)).isInstanceOf(SystemMessage.class);
		assertThat(messages.get(1)).isInstanceOf(UserMessage.class);
		assertThat(messages.get(0).getText()).isEqualTo("system instruction");
		assertThat(messages.get(1).getText()).isEqualTo("sanitized user payload");
		assertThat(prompt.getOptions()).isInstanceOf(BedrockChatOptions.class);
		BedrockChatOptions options = (BedrockChatOptions) prompt.getOptions();
		assertThat(options.getModel()).isEqualTo("amazon.nova-micro-v1:0");
		assertThat(options.getTemperature()).isEqualTo(0.0d);
		assertThat(options.getMaxTokens()).isEqualTo(expectedMaxTokens);
	}

	private BedrockNovaLocalGroundingProvider provider(
		ChatModel chatModel,
		int validationMaxTokens,
		int repairMaxTokens
	) {
		return new BedrockNovaLocalGroundingProvider(
			chatModel,
			answerProperties(),
			new LocalGroundingProperties(
				"question-grounding-validation-v1",
				"question-grounding-repair-v1",
				validationMaxTokens,
				repairMaxTokens,
				Duration.ofSeconds(30)
			),
			Clock.fixed(STARTED_AT, ZoneOffset.UTC)
		);
	}

	private LocalAnswerProperties answerProperties() {
		return new LocalAnswerProperties(
			"amazon.nova-micro-v1:0",
			"gemini-3.1-flash-lite",
			"test-key",
			"question-local-answer-v1",
			1024,
			Duration.ofSeconds(30)
		);
	}

	private GroundingModelPrompt modelPrompt() {
		return new GroundingModelPrompt("system instruction", "sanitized user payload");
	}

	private static ChatResponse response(String output) {
		return new ChatResponse(List.of(new Generation(new AssistantMessage(output))));
	}

	private static String validationOutput() {
		return "{\"supported\":true,\"score\":0.93,\"unsupportedClaims\":[]}";
	}

	private static String repairOutput() {
		return "{\"answer\":\"앞문으로 승차하세요.\",\"citations\":[{\"evidenceIndex\":0,\"startIndex\":0,\"endIndex\":10}]}";
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
