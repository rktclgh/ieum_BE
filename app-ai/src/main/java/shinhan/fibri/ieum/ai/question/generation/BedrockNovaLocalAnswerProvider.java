package shinhan.fibri.ieum.ai.question.generation;

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
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.exception.SdkServiceException;

final class BedrockNovaLocalAnswerProvider implements LocalAnswerProvider {

	private static final String PROVIDER = "bedrock";
	private static final double TEMPERATURE = 0.0d;

	private final ChatModel chatModel;
	private final LocalAnswerPromptFactory promptFactory;
	private final LocalAnswerProperties properties;
	private final Clock clock;

	BedrockNovaLocalAnswerProvider(
		ChatModel chatModel,
		LocalAnswerPromptFactory promptFactory,
		LocalAnswerProperties properties,
		Clock clock
	) {
		this.chatModel = Objects.requireNonNull(chatModel, "chatModel must not be null");
		this.promptFactory = Objects.requireNonNull(promptFactory, "promptFactory must not be null");
		this.properties = Objects.requireNonNull(properties, "properties must not be null");
		this.clock = Objects.requireNonNull(clock, "clock must not be null");
	}

	@Override
	public String provider() {
		return PROVIDER;
	}

	@Override
	public String model() {
		return properties.primaryModel();
	}

	@Override
	public LocalAnswerProviderResponse generate(LocalAnswerPrompt prompt) {
		LocalAnswerModelPrompt modelPrompt = promptFactory.create(prompt);
		Instant startedAt = clock.instant();
		try {
			ChatResponse response = chatModel.call(chatPrompt(modelPrompt));
			return response(response, startedAt);
		}
		catch (LocalAnswerProviderException exception) {
			throw exception;
		}
		catch (RuntimeException exception) {
			throw new LocalAnswerProviderException(errorCode(exception));
		}
	}

	private Prompt chatPrompt(LocalAnswerModelPrompt modelPrompt) {
		BedrockChatOptions options = BedrockChatOptions.builder()
			.model(properties.primaryModel())
			.temperature(TEMPERATURE)
			.maxTokens(properties.maxTokens())
			.build();
		return new Prompt(
			List.of(
				new SystemMessage(modelPrompt.systemInstruction()),
				new UserMessage(modelPrompt.userInstruction())
			),
			options
		);
	}

	private LocalAnswerProviderResponse response(ChatResponse response, Instant startedAt) {
		if (response == null) {
			throw new LocalAnswerProviderException(LocalAnswerProviderFailureCode.empty_response);
		}
		Generation generation = response.getResult();
		if (generation == null || generation.getOutput() == null) {
			throw new LocalAnswerProviderException(LocalAnswerProviderFailureCode.empty_response);
		}
		ChatResponseMetadata metadata = response.getMetadata();
		Usage usage = metadata == null ? null : metadata.getUsage();
		return new LocalAnswerProviderResponse(
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
