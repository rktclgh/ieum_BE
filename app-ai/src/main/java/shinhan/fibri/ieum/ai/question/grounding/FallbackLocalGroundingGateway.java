package shinhan.fibri.ieum.ai.question.grounding;

import java.time.Duration;
import java.util.Objects;
import shinhan.fibri.ieum.ai.question.generation.GeneratedAnswer;
import shinhan.fibri.ieum.ai.question.generation.InvalidLocalAnswerOutputException;
import shinhan.fibri.ieum.ai.question.generation.LocalAnswerOutputParser;
import shinhan.fibri.ieum.ai.question.generation.LocalAnswerProviderFailureCode;
import shinhan.fibri.ieum.ai.question.generation.ParsedLocalAnswer;

final class FallbackLocalGroundingGateway implements LocalGroundingGateway {

	private final LocalGroundingProvider primaryProvider;
	private final LocalGroundingProvider fallbackProvider;
	private final GroundingValidationPromptFactory validationPromptFactory;
	private final GroundingRepairPromptFactory repairPromptFactory;
	private final GroundingValidationOutputParser validationOutputParser;
	private final LocalAnswerOutputParser repairOutputParser;
	private final LocalGroundingProperties properties;

	FallbackLocalGroundingGateway(
		LocalGroundingProvider primaryProvider,
		LocalGroundingProvider fallbackProvider,
		GroundingValidationPromptFactory validationPromptFactory,
		GroundingRepairPromptFactory repairPromptFactory,
		GroundingValidationOutputParser validationOutputParser,
		LocalAnswerOutputParser repairOutputParser,
		LocalGroundingProperties properties
	) {
		this.primaryProvider = Objects.requireNonNull(primaryProvider, "primaryProvider must not be null");
		this.fallbackProvider = Objects.requireNonNull(fallbackProvider, "fallbackProvider must not be null");
		this.validationPromptFactory = Objects.requireNonNull(
			validationPromptFactory,
			"validationPromptFactory must not be null"
		);
		this.repairPromptFactory = Objects.requireNonNull(
			repairPromptFactory,
			"repairPromptFactory must not be null"
		);
		this.validationOutputParser = Objects.requireNonNull(
			validationOutputParser,
			"validationOutputParser must not be null"
		);
		this.repairOutputParser = Objects.requireNonNull(
			repairOutputParser,
			"repairOutputParser must not be null"
		);
		this.properties = Objects.requireNonNull(properties, "properties must not be null");
	}

	@Override
	public GroundingValidationResult validate(LocalGroundingRequest request, Duration timeout) {
		Objects.requireNonNull(request, "request must not be null");
		requireTimeout(timeout);
		GroundingModelPrompt prompt = validationPromptFactory.create(request);

		ProviderAttempt<SuccessfulValidation> primary = validationAttempt(primaryProvider, prompt);
		if (primary.value() != null) {
			return validationResult(primaryProvider, primary.value(), null);
		}

		ProviderAttempt<SuccessfulValidation> fallback = validationAttempt(fallbackProvider, prompt);
		if (fallback.value() != null) {
			return validationResult(
				fallbackProvider,
				fallback.value(),
				"validation_primary_" + primary.failureCode().name()
			);
		}

		throw unavailable(primary, fallback);
	}

	@Override
	public GeneratedAnswer repair(
		LocalGroundingRequest request,
		GroundingValidation failedValidation,
		Duration timeout
	) {
		Objects.requireNonNull(request, "request must not be null");
		Objects.requireNonNull(failedValidation, "failedValidation must not be null");
		requireTimeout(timeout);
		GroundingModelPrompt prompt = repairPromptFactory.create(request, failedValidation);

		ProviderAttempt<SuccessfulRepair> primary = repairAttempt(primaryProvider, prompt, request);
		if (primary.value() != null) {
			return repairedAnswer(primaryProvider, primary.value(), null);
		}

		ProviderAttempt<SuccessfulRepair> fallback = repairAttempt(fallbackProvider, prompt, request);
		if (fallback.value() != null) {
			return repairedAnswer(
				fallbackProvider,
				fallback.value(),
				"repair_primary_" + primary.failureCode().name()
			);
		}

		throw unavailable(primary, fallback);
	}

	private ProviderAttempt<SuccessfulValidation> validationAttempt(
		LocalGroundingProvider provider,
		GroundingModelPrompt prompt
	) {
		try {
			GroundingProviderResponse response = provider.validate(prompt);
			if (response == null || response.rawOutput() == null || response.rawOutput().isBlank()) {
				return ProviderAttempt.failure(LocalAnswerProviderFailureCode.empty_response);
			}
			return ProviderAttempt.success(new SuccessfulValidation(
				validationOutputParser.parse(response.rawOutput()),
				response
			));
		}
		catch (GroundingProviderException exception) {
			return ProviderAttempt.failure(exception.failureCode());
		}
		catch (InvalidGroundingValidationOutputException exception) {
			return ProviderAttempt.failure(LocalAnswerProviderFailureCode.invalid_output);
		}
		catch (RuntimeException exception) {
			return ProviderAttempt.failure(LocalAnswerProviderFailureCode.provider_unavailable);
		}
	}

	private GroundingValidationResult validationResult(
		LocalGroundingProvider provider,
		SuccessfulValidation validation,
		String fallbackReason
	) {
		return new GroundingValidationResult(
			validation.validation(),
			provider.provider(),
			provider.model(),
			properties.validationPromptVersion(),
			validation.response().startedAt(),
			validation.response().inputTokenCount(),
			validation.response().outputTokenCount(),
			validation.response().providerRequestId(),
			fallbackReason
		);
	}

	private ProviderAttempt<SuccessfulRepair> repairAttempt(
		LocalGroundingProvider provider,
		GroundingModelPrompt prompt,
		LocalGroundingRequest request
	) {
		try {
			GroundingProviderResponse response = provider.repair(prompt);
			if (response == null) {
				return ProviderAttempt.failure(LocalAnswerProviderFailureCode.empty_response);
			}
			ParsedLocalAnswer parsed = repairOutputParser.parse(response.rawOutput(), request.prompt());
			return ProviderAttempt.success(new SuccessfulRepair(parsed, response));
		}
		catch (GroundingProviderException exception) {
			return ProviderAttempt.failure(exception.failureCode());
		}
		catch (InvalidLocalAnswerOutputException exception) {
			return ProviderAttempt.failure(exception.failureCode());
		}
		catch (RuntimeException exception) {
			return ProviderAttempt.failure(LocalAnswerProviderFailureCode.provider_unavailable);
		}
	}

	private GeneratedAnswer repairedAnswer(
		LocalGroundingProvider provider,
		SuccessfulRepair repair,
		String fallbackReason
	) {
		return new GeneratedAnswer(
			repair.parsed().answer(),
			repair.parsed().citations(),
			provider.provider(),
			provider.model(),
			properties.repairPromptVersion(),
			repair.response().startedAt(),
			repair.response().inputTokenCount(),
			repair.response().outputTokenCount(),
			repair.response().providerRequestId(),
			fallbackReason
		);
	}

	private QuestionGroundingUnavailableException unavailable(
		ProviderAttempt<?> primary,
		ProviderAttempt<?> fallback
	) {
		return new QuestionGroundingUnavailableException(
			primary.failureCode(),
			fallback.failureCode()
		);
	}

	private void requireTimeout(Duration timeout) {
		if (!properties.modelTimeout().equals(timeout)) {
			throw new IllegalArgumentException("Local grounding timeout must be 30 seconds");
		}
	}

	private record SuccessfulRepair(
		ParsedLocalAnswer parsed,
		GroundingProviderResponse response
	) {

		private SuccessfulRepair {
			Objects.requireNonNull(parsed, "parsed must not be null");
			Objects.requireNonNull(response, "response must not be null");
		}
	}

	private record SuccessfulValidation(
		GroundingValidation validation,
		GroundingProviderResponse response
	) {

		private SuccessfulValidation {
			Objects.requireNonNull(validation, "validation must not be null");
			Objects.requireNonNull(response, "response must not be null");
		}
	}

	private record ProviderAttempt<T>(
		T value,
		LocalAnswerProviderFailureCode failureCode
	) {

		private ProviderAttempt {
			if ((value == null) == (failureCode == null)) {
				throw new IllegalArgumentException("Provider attempt must contain exactly one outcome");
			}
		}

		private static <T> ProviderAttempt<T> success(T value) {
			return new ProviderAttempt<>(Objects.requireNonNull(value), null);
		}

		private static <T> ProviderAttempt<T> failure(LocalAnswerProviderFailureCode failureCode) {
			return new ProviderAttempt<>(null, Objects.requireNonNull(failureCode));
		}
	}
}
