package shinhan.fibri.ieum.ai.question.grounding;

import java.net.SocketTimeoutException;
import java.time.Clock;
import java.time.Instant;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import org.springframework.ai.bedrock.converse.BedrockChatOptions;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import shinhan.fibri.ieum.ai.question.generation.LocalAnswerProperties;
import shinhan.fibri.ieum.ai.question.generation.LocalAnswerProviderFailureCode;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.exception.SdkServiceException;

final class BedrockNovaLocalGroundingProvider implements LocalGroundingProvider {

	private static final String PROVIDER = "bedrock";
	private static final double TEMPERATURE = 0.0d;

	private final ChatModel chatModel;
	private final LocalAnswerProperties answerProperties;
	private final LocalGroundingProperties groundingProperties;
	private final Clock clock;

	BedrockNovaLocalGroundingProvider(
		ChatModel chatModel,
		LocalAnswerProperties answerProperties,
		LocalGroundingProperties groundingProperties,
		Clock clock
	) {
		this.chatModel = Objects.requireNonNull(chatModel, "chatModel must not be null");
		this.answerProperties = Objects.requireNonNull(answerProperties, "answerProperties must not be null");
		this.groundingProperties = Objects.requireNonNull(
			groundingProperties,
			"groundingProperties must not be null"
		);
		this.clock = Objects.requireNonNull(clock, "clock must not be null");
	}

	@Override
	public String provider() {
		return PROVIDER;
	}

	@Override
	public String model() {
		return answerProperties.primaryModel();
	}

	@Override
	public GroundingProviderResponse validate(GroundingModelPrompt prompt) {
		return invoke(prompt, groundingProperties.validationMaxTokens());
	}

	@Override
	public GroundingProviderResponse repair(GroundingModelPrompt prompt) {
		return invoke(prompt, groundingProperties.repairMaxTokens());
	}

	private GroundingProviderResponse invoke(GroundingModelPrompt modelPrompt, int maxTokens) {
		Objects.requireNonNull(modelPrompt, "modelPrompt must not be null");
		Instant startedAt = clock.instant();
		try {
			ChatResponse response = chatModel.call(chatPrompt(modelPrompt, maxTokens));
			return response(response, startedAt);
		}
		catch (GroundingProviderException exception) {
			throw exception;
		}
		catch (RuntimeException exception) {
			throw new GroundingProviderException(errorCode(exception));
		}
	}

	private Prompt chatPrompt(GroundingModelPrompt modelPrompt, int maxTokens) {
		BedrockChatOptions options = BedrockChatOptions.builder()
			.model(answerProperties.primaryModel())
			.temperature(TEMPERATURE)
			.maxTokens(maxTokens)
			.build();
		return new Prompt(
			List.of(
				new SystemMessage(modelPrompt.systemInstruction()),
				new UserMessage(modelPrompt.userInstruction())
			),
			options
		);
	}

	private GroundingProviderResponse response(ChatResponse response, Instant startedAt) {
		if (response == null) {
			throw new GroundingProviderException(LocalAnswerProviderFailureCode.empty_response);
		}
		Generation generation = response.getResult();
		if (generation == null || generation.getOutput() == null) {
			throw new GroundingProviderException(LocalAnswerProviderFailureCode.empty_response);
		}
		ChatResponseMetadata metadata = response.getMetadata();
		Usage usage = metadata == null ? null : metadata.getUsage();
		return new GroundingProviderResponse(
			generation.getOutput().getText(),
			startedAt,
			usage == null ? null : usage.getPromptTokens(),
			usage == null ? null : usage.getCompletionTokens(),
			metadata == null ? null : metadata.getId()
		);
	}

	private LocalAnswerProviderFailureCode errorCode(RuntimeException exception) {
		Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
		Throwable current = exception;
		while (current != null && visited.add(current)) {
			if (current instanceof SdkServiceException serviceException) {
				if (serviceException.isThrottlingException() || serviceException.statusCode() == 429) {
					return LocalAnswerProviderFailureCode.rate_limited;
				}
				if (serviceException.statusCode() == 408 || serviceException.statusCode() == 504) {
					return LocalAnswerProviderFailureCode.timeout;
				}
				return LocalAnswerProviderFailureCode.provider_unavailable;
			}
			if (current instanceof SdkException sdkException) {
				return sdkException.retryable()
					? LocalAnswerProviderFailureCode.timeout
					: LocalAnswerProviderFailureCode.provider_unavailable;
			}
			if (current instanceof TimeoutException || current instanceof SocketTimeoutException) {
				return LocalAnswerProviderFailureCode.timeout;
			}
			current = current.getCause();
		}
		return LocalAnswerProviderFailureCode.provider_unavailable;
	}
}
