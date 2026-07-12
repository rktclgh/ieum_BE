package shinhan.fibri.ieum.ai.report.service;

import java.net.SocketTimeoutException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeoutException;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import shinhan.fibri.ieum.ai.config.ReportModelProperties;
import shinhan.fibri.ieum.ai.report.domain.ReportModelReviewOutput;
import shinhan.fibri.ieum.ai.report.domain.ReportPolicySnapshot;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.exception.SdkServiceException;

public class BedrockReportReviewModelProvider implements ReportReviewModelProvider {

	private static final String PROVIDER = "bedrock";

	private final ChatModel chatModel;
	private final ReportReviewModelPromptFactory promptFactory;
	private final ReportReviewModelOutputParser outputParser;
	private final String model;

	public BedrockReportReviewModelProvider(
		ChatModel chatModel,
		ReportReviewModelPromptFactory promptFactory,
		ReportReviewModelOutputParser outputParser,
		ReportModelProperties properties
	) {
		this.chatModel = Objects.requireNonNull(chatModel, "chatModel must not be null");
		this.promptFactory = Objects.requireNonNull(promptFactory, "promptFactory must not be null");
		this.outputParser = Objects.requireNonNull(outputParser, "outputParser must not be null");
		this.model = Objects.requireNonNull(properties, "properties must not be null").novaModel();
	}

	@Override
	public String provider() {
		return PROVIDER;
	}

	@Override
	public String model() {
		return model;
	}

	@Override
	public ReportModelReviewOutput review(PreparedReportReview preparedReview, ReportPolicySnapshot policySnapshot) {
		ReportReviewModelPrompt prompt = promptFactory.create(preparedReview, policySnapshot);
		try {
			return outputParser.parse(rawOutput(chatModel.call(chatPrompt(prompt))));
		} catch (InvalidReportModelOutputException exception) {
			throw exception;
		} catch (RuntimeException exception) {
			throw new ReportReviewModelProviderException(errorCode(exception));
		}
	}

	private Prompt chatPrompt(ReportReviewModelPrompt prompt) {
		return new Prompt(
			new SystemMessage(prompt.systemInstruction()),
			UserMessage.builder()
				.text(prompt.userInstruction())
				.media(media(prompt.images()))
				.build()
		);
	}

	private List<Media> media(List<ReportReviewModelPromptImage> images) {
		return images.stream()
			.map(image -> Media.builder()
				.mimeType(Media.Format.IMAGE_WEBP)
				.data(image.bytes())
				.build())
			.toList();
	}

	private String rawOutput(ChatResponse response) {
		if (response == null) {
			throw new IllegalStateException("empty chat response");
		}
		Generation generation = response.getResult();
		if (generation == null || generation.getOutput() == null) {
			throw new IllegalStateException("empty chat generation");
		}
		return generation.getOutput().getText();
	}

	private ReportReviewProviderErrorCode errorCode(RuntimeException exception) {
		Throwable current = exception;
		while (current != null) {
			if (current instanceof SdkServiceException sdkServiceException) {
				return serviceErrorCode(sdkServiceException);
			}
			if (current instanceof SdkException sdkException) {
				return sdkException.retryable()
					? ReportReviewProviderErrorCode.timeout
					: ReportReviewProviderErrorCode.transport_error;
			}
			if (current instanceof TimeoutException || current instanceof SocketTimeoutException) {
				return ReportReviewProviderErrorCode.timeout;
			}
			current = current.getCause();
		}
		return ReportReviewProviderErrorCode.transport_error;
	}

	private ReportReviewProviderErrorCode serviceErrorCode(SdkServiceException exception) {
		if (exception.isThrottlingException() || exception.statusCode() == 429) {
			return ReportReviewProviderErrorCode.rate_limited;
		}
		if (exception.statusCode() == 408 || exception.statusCode() == 504) {
			return ReportReviewProviderErrorCode.timeout;
		}
		if (exception.statusCode() >= 500) {
			return ReportReviewProviderErrorCode.server_error;
		}
		return ReportReviewProviderErrorCode.transport_error;
	}
}
