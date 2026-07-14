package shinhan.fibri.ieum.ai.question.grounding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import shinhan.fibri.ieum.ai.question.citation.AnswerCitation;
import shinhan.fibri.ieum.ai.question.generation.GeneratedAnswer;
import shinhan.fibri.ieum.ai.question.generation.LocalAnswerEvidence;
import shinhan.fibri.ieum.ai.question.generation.LocalAnswerOutputParser;
import shinhan.fibri.ieum.ai.question.generation.LocalAnswerPrompt;
import shinhan.fibri.ieum.ai.question.generation.LocalAnswerProviderFailureCode;
import shinhan.fibri.ieum.ai.question.generation.LocalAnswerRegion;

class FallbackLocalGroundingGatewayTest {

	private static final Instant STARTED_AT = Instant.parse("2026-07-13T10:15:30Z");

	@Test
	void validUnsupportedVerdictIsANormalPrimaryResultAndNeverCallsFallback() {
		CountingProvider primary = provider(
			"bedrock",
			"amazon.nova-micro-v1:0",
			validation(false),
			repairedAnswer(),
			null
		);
		CountingProvider fallback = provider(
			"gemini",
			"gemini-3.1-flash-lite",
			validation(true),
			repairedAnswer(),
			null
		);

		GroundingValidationResult result = gateway(primary, fallback)
			.validate(request(), Duration.ofSeconds(30));

		assertThat(result.validation().supported()).isFalse();
		assertThat(result.validation().unsupportedClaims()).containsExactly("근거가 부족합니다.");
		assertThat(result.provider()).isEqualTo("bedrock");
		assertThat(result.model()).isEqualTo("amazon.nova-micro-v1:0");
		assertThat(result.promptVersion()).isEqualTo("question-grounding-validation-v1");
		assertThat(result.startedAt()).isEqualTo(STARTED_AT);
		assertThat(result.inputTokenCount()).isEqualTo(31);
		assertThat(result.outputTokenCount()).isEqualTo(13);
		assertThat(result.providerRequestId()).isEqualTo("grounding-request-1");
		assertThat(result.fallbackReason()).isNull();
		assertThat(primary.validationCalls()).isEqualTo(1);
		assertThat(fallback.validationCalls()).isZero();
	}

	@Test
	void validationFallsBackExactlyOnceOnlyAfterPrimaryFailure() {
		CountingProvider primary = provider(
			"bedrock",
			"amazon.nova-micro-v1:0",
			"```json\n" + validation(true) + "\n```",
			repairedAnswer(),
			null
		);
		CountingProvider fallback = provider(
			"gemini",
			"gemini-3.1-flash-lite",
			validation(true),
			repairedAnswer(),
			null
		);

		GroundingValidationResult result = gateway(primary, fallback)
			.validate(request(), Duration.ofSeconds(30));

		assertThat(result.validation().supported()).isTrue();
		assertThat(result.provider()).isEqualTo("gemini");
		assertThat(result.model()).isEqualTo("gemini-3.1-flash-lite");
		assertThat(result.promptVersion()).isEqualTo("question-grounding-validation-v1");
		assertThat(result.startedAt()).isEqualTo(STARTED_AT);
		assertThat(result.inputTokenCount()).isEqualTo(31);
		assertThat(result.outputTokenCount()).isEqualTo(13);
		assertThat(result.providerRequestId()).isEqualTo("grounding-request-1");
		assertThat(result.fallbackReason()).isEqualTo("validation_primary_invalid_output");
		assertThat(primary.validationCalls()).isEqualTo(1);
		assertThat(fallback.validationCalls()).isEqualTo(1);
	}

	@Test
	void bothValidationFailuresExposeOnlyFiniteSanitizedCodes() {
		CountingProvider primary = provider(
			"bedrock",
			"amazon.nova-micro-v1:0",
			null,
			repairedAnswer(),
			LocalAnswerProviderFailureCode.timeout
		);
		CountingProvider fallback = provider(
			"gemini",
			"gemini-3.1-flash-lite",
			"raw malformed secret prompt",
			repairedAnswer(),
			null
		);

		assertThatThrownBy(() -> gateway(primary, fallback).validate(request(), Duration.ofSeconds(30)))
			.isInstanceOfSatisfying(QuestionGroundingUnavailableException.class, exception -> {
				assertThat(exception.primaryFailure()).isEqualTo(LocalAnswerProviderFailureCode.timeout);
				assertThat(exception.fallbackFailure()).isEqualTo(LocalAnswerProviderFailureCode.invalid_output);
				assertThat(exception)
					.hasMessageNotContaining("raw")
					.hasMessageNotContaining("secret")
					.hasMessageNotContaining("prompt");
				assertThat(exception.getCause()).isNull();
			});
		assertThat(primary.validationCalls()).isEqualTo(1);
		assertThat(fallback.validationCalls()).isEqualTo(1);
	}

	@Test
	void novaRepairSuccessRecordsActualRepairProvenance() {
		CountingProvider primary = provider(
			"bedrock",
			"amazon.nova-micro-v1:0",
			validation(true),
			repairedAnswer(),
			null
		);
		CountingProvider fallback = provider(
			"gemini",
			"gemini-3.1-flash-lite",
			validation(true),
			repairedAnswer(),
			null
		);

		GeneratedAnswer result = gateway(primary, fallback).repair(
			request(),
			failedValidation(),
			Duration.ofSeconds(30)
		);

		assertThat(result.answer()).isEqualTo("앞문으로 승차하세요.");
		assertThat(result.citations()).containsExactly(new AnswerCitation(0, 0, 10));
		assertThat(result.provider()).isEqualTo("bedrock");
		assertThat(result.model()).isEqualTo("amazon.nova-micro-v1:0");
		assertThat(result.promptVersion()).isEqualTo("question-grounding-repair-v1");
		assertThat(result.startedAt()).isEqualTo(STARTED_AT);
		assertThat(result.inputTokenCount()).isEqualTo(31);
		assertThat(result.outputTokenCount()).isEqualTo(13);
		assertThat(result.providerRequestId()).isEqualTo("grounding-request-1");
		assertThat(result.fallbackReason()).isNull();
		assertThat(primary.repairCalls()).isEqualTo(1);
		assertThat(fallback.repairCalls()).isZero();
	}

	@Test
	void geminiRepairSuccessRecordsFallbackReasonFromFinitePrimaryFailure() {
		CountingProvider primary = provider(
			"bedrock",
			"amazon.nova-micro-v1:0",
			validation(true),
			null,
			LocalAnswerProviderFailureCode.timeout
		);
		CountingProvider fallback = provider(
			"gemini",
			"gemini-3.1-flash-lite",
			validation(true),
			repairedAnswer(),
			null
		);

		GeneratedAnswer result = gateway(primary, fallback).repair(
			request(),
			failedValidation(),
			Duration.ofSeconds(30)
		);

		assertThat(result.provider()).isEqualTo("gemini");
		assertThat(result.model()).isEqualTo("gemini-3.1-flash-lite");
		assertThat(result.promptVersion()).isEqualTo("question-grounding-repair-v1");
		assertThat(result.fallbackReason()).isEqualTo("repair_primary_timeout");
		assertThat(primary.repairCalls()).isEqualTo(1);
		assertThat(fallback.repairCalls()).isEqualTo(1);
	}

	@Test
	void strictRepairParsingTriggersFallbackAndBothFailuresExposeOnlyFiniteCodes() {
		CountingProvider primary = provider(
			"bedrock",
			"amazon.nova-micro-v1:0",
			validation(true),
			"{} trailing",
			null
		);
		CountingProvider fallback = provider(
			"gemini",
			"gemini-3.1-flash-lite",
			validation(true),
			null,
			LocalAnswerProviderFailureCode.provider_unavailable
		);

		assertThatThrownBy(() -> gateway(primary, fallback).repair(
			request(),
			failedValidation(),
			Duration.ofSeconds(30)
		)).isInstanceOfSatisfying(QuestionGroundingUnavailableException.class, exception -> {
			assertThat(exception.primaryFailure()).isEqualTo(LocalAnswerProviderFailureCode.invalid_output);
			assertThat(exception.fallbackFailure())
				.isEqualTo(LocalAnswerProviderFailureCode.provider_unavailable);
			assertThat(exception).hasMessage("Local question grounding is unavailable");
			assertThat(exception)
				.hasMessageNotContaining("버스")
				.hasMessageNotContaining("raw")
				.hasMessageNotContaining("secret")
				.hasMessageNotContaining("trailing");
			assertThat(exception.getCause()).isNull();
		});
		assertThat(primary.repairCalls()).isEqualTo(1);
		assertThat(fallback.repairCalls()).isEqualTo(1);
	}

	@Test
	void rejectsAnyBudgetOtherThanThirtySecondsBeforeCallingProviders() {
		CountingProvider primary = provider(
			"bedrock",
			"amazon.nova-micro-v1:0",
			validation(true),
			repairedAnswer(),
			null
		);
		CountingProvider fallback = provider(
			"gemini",
			"gemini-3.1-flash-lite",
			validation(true),
			repairedAnswer(),
			null
		);
		LocalGroundingGateway gateway = gateway(primary, fallback);

		assertThatThrownBy(() -> gateway.validate(request(), Duration.ofSeconds(31)))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("30 seconds");
		assertThatThrownBy(() -> gateway.repair(request(), failedValidation(), Duration.ofSeconds(29)))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("30 seconds");
		assertThat(primary.validationCalls()).isZero();
		assertThat(primary.repairCalls()).isZero();
		assertThat(fallback.validationCalls()).isZero();
		assertThat(fallback.repairCalls()).isZero();
	}

	private LocalGroundingGateway gateway(
		LocalGroundingProvider primary,
		LocalGroundingProvider fallback
	) {
		ObjectMapper objectMapper = new ObjectMapper();
		return new FallbackLocalGroundingGateway(
			primary,
			fallback,
			new GroundingValidationPromptFactory(objectMapper),
			new GroundingRepairPromptFactory(objectMapper),
			new GroundingValidationOutputParser(objectMapper),
			new LocalAnswerOutputParser(objectMapper),
			properties()
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

	private CountingProvider provider(
		String provider,
		String model,
		String validationOutput,
		String repairOutput,
		LocalAnswerProviderFailureCode failureCode
	) {
		return new CountingProvider(provider, model, validationOutput, repairOutput, failureCode);
	}

	private LocalGroundingRequest request() {
		return new LocalGroundingRequest(
			new LocalAnswerPrompt(
				"버스 이용",
				"앞문으로 타나요?",
				LocalAnswerRegion.korea("서울특별시", "중구", null),
				List.of(new LocalAnswerEvidence(
					0,
					"버스 이용 안내",
					"앞문으로 승차하고 뒷문으로 하차합니다.",
					"government"
				))
			),
			new GeneratedAnswer(
				"앞문으로 타세요.",
				List.of(new AnswerCitation(0, 0, 9)),
				"bedrock",
				"amazon.nova-micro-v1:0",
				"question-local-answer-v1",
				STARTED_AT,
				20,
				10,
				"generation-request-1",
				null
			)
		);
	}

	private GroundingValidation failedValidation() {
		return new GroundingValidation(false, new java.math.BigDecimal("0.41"), List.of("근거가 부족합니다."));
	}

	private static String validation(boolean supported) {
		return supported
			? "{\"supported\":true,\"score\":0.93,\"unsupportedClaims\":[]}"
			: "{\"supported\":false,\"score\":0.41,\"unsupportedClaims\":[\"근거가 부족합니다.\"]}";
	}

	private static String repairedAnswer() {
		return "{\"answer\":\"앞문으로 승차하세요.\",\"citations\":[{\"evidenceIndex\":0,\"startIndex\":0,\"endIndex\":10}]}";
	}

	private static final class CountingProvider implements LocalGroundingProvider {

		private final String provider;
		private final String model;
		private final String validationOutput;
		private final String repairOutput;
		private final LocalAnswerProviderFailureCode failureCode;
		private final AtomicInteger validationCalls = new AtomicInteger();
		private final AtomicInteger repairCalls = new AtomicInteger();

		private CountingProvider(
			String provider,
			String model,
			String validationOutput,
			String repairOutput,
			LocalAnswerProviderFailureCode failureCode
		) {
			this.provider = provider;
			this.model = model;
			this.validationOutput = validationOutput;
			this.repairOutput = repairOutput;
			this.failureCode = failureCode;
		}

		@Override
		public String provider() {
			return provider;
		}

		@Override
		public String model() {
			return model;
		}

		@Override
		public GroundingProviderResponse validate(GroundingModelPrompt prompt) {
			validationCalls.incrementAndGet();
			return response(validationOutput);
		}

		@Override
		public GroundingProviderResponse repair(GroundingModelPrompt prompt) {
			repairCalls.incrementAndGet();
			return response(repairOutput);
		}

		private GroundingProviderResponse response(String output) {
			if (failureCode != null) {
				throw new GroundingProviderException(failureCode);
			}
			return new GroundingProviderResponse(output, STARTED_AT, 31, 13, "grounding-request-1");
		}

		private int validationCalls() {
			return validationCalls.get();
		}

		private int repairCalls() {
			return repairCalls.get();
		}
	}
}
