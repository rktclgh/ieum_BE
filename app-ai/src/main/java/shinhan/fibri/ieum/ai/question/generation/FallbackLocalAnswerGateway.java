package shinhan.fibri.ieum.ai.question.generation;

import java.time.Duration;
import java.util.Objects;

final class FallbackLocalAnswerGateway implements LocalAnswerGateway {

	private final LocalAnswerProvider primaryProvider;
	private final LocalAnswerProvider fallbackProvider;
	private final LocalAnswerOutputParser outputParser;
	private final LocalAnswerProperties properties;

	FallbackLocalAnswerGateway(
		LocalAnswerProvider primaryProvider,
		LocalAnswerProvider fallbackProvider,
		LocalAnswerOutputParser outputParser,
		LocalAnswerProperties properties
	) {
		this.primaryProvider = Objects.requireNonNull(primaryProvider, "primaryProvider must not be null");
		this.fallbackProvider = Objects.requireNonNull(fallbackProvider, "fallbackProvider must not be null");
		this.outputParser = Objects.requireNonNull(outputParser, "outputParser must not be null");
		this.properties = Objects.requireNonNull(properties, "properties must not be null");
	}

	@Override
	public GeneratedAnswer generate(LocalAnswerPrompt prompt, Duration timeout) {
		Objects.requireNonNull(prompt, "prompt must not be null");
		if (!properties.modelTimeout().equals(timeout)) {
			throw new IllegalArgumentException("Local answer timeout must be 30 seconds");
		}

		ProviderAttempt primary = attempt(primaryProvider, prompt);
		if (primary.answer() != null) {
			return result(primaryProvider, primary.answer(), null);
		}

		ProviderAttempt fallback = attempt(fallbackProvider, prompt);
		if (fallback.answer() != null) {
			return result(
				fallbackProvider,
				fallback.answer(),
				"primary_" + primary.failureCode().name()
			);
		}

		throw new QuestionGenerationUnavailableException(
			primary.failureCode(),
			fallback.failureCode()
		);
	}

	private ProviderAttempt attempt(LocalAnswerProvider provider, LocalAnswerPrompt prompt) {
		try {
			LocalAnswerProviderResponse response = provider.generate(prompt);
			if (response == null) {
				return ProviderAttempt.failure(LocalAnswerProviderFailureCode.empty_response);
			}
			ParsedLocalAnswer parsed = outputParser.parse(response.rawOutput(), prompt);
			return ProviderAttempt.success(new SuccessfulProviderAnswer(parsed, response));
		}
		catch (LocalAnswerProviderException exception) {
			return ProviderAttempt.failure(exception.failureCode());
		}
		catch (RuntimeException exception) {
			return ProviderAttempt.failure(LocalAnswerProviderFailureCode.provider_unavailable);
		}
	}

	private GeneratedAnswer result(
		LocalAnswerProvider provider,
		SuccessfulProviderAnswer answer,
		String fallbackReason
	) {
		return new GeneratedAnswer(
			answer.parsed().answer(),
			answer.parsed().citations(),
			provider.provider(),
			provider.model(),
			properties.promptVersion(),
			answer.response().startedAt(),
			answer.response().inputTokenCount(),
			answer.response().outputTokenCount(),
			answer.response().providerRequestId(),
			fallbackReason
		);
	}

	private record SuccessfulProviderAnswer(
		ParsedLocalAnswer parsed,
		LocalAnswerProviderResponse response
	) {
	}

	private record ProviderAttempt(
		SuccessfulProviderAnswer answer,
		LocalAnswerProviderFailureCode failureCode
	) {

		private ProviderAttempt {
			if ((answer == null) == (failureCode == null)) {
				throw new IllegalArgumentException("Provider attempt must contain exactly one outcome");
			}
		}

		static ProviderAttempt success(SuccessfulProviderAnswer answer) {
			return new ProviderAttempt(Objects.requireNonNull(answer), null);
		}

		static ProviderAttempt failure(LocalAnswerProviderFailureCode failureCode) {
			return new ProviderAttempt(null, Objects.requireNonNull(failureCode));
		}
	}
}
