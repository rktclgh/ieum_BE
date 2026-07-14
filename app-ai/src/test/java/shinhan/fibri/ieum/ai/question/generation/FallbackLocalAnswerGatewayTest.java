package shinhan.fibri.ieum.ai.question.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import shinhan.fibri.ieum.ai.question.citation.AnswerCitation;

class FallbackLocalAnswerGatewayTest {

	private static final Instant STARTED_AT = Instant.parse("2026-07-13T10:15:30Z");

	@Test
	void returnsPrimaryProvenanceWithoutCallingFallback() {
		CountingProvider primary = provider("bedrock", "amazon.nova-micro-v1:0", validOutput(), null);
		CountingProvider fallback = provider("gemini", "gemini-3.1-flash-lite", validOutput(), null);
		LocalAnswerGateway gateway = gateway(primary, fallback);

		GeneratedAnswer result = gateway.generate(prompt(), Duration.ofSeconds(30));

		assertThat(primary.calls()).isEqualTo(1);
		assertThat(fallback.calls()).isZero();
		assertThat(result.answer()).isEqualTo("앞문으로 타세요.");
		assertThat(result.citations()).containsExactly(new AnswerCitation(0, 0, 9));
		assertThat(result.provider()).isEqualTo("bedrock");
		assertThat(result.model()).isEqualTo("amazon.nova-micro-v1:0");
		assertThat(result.promptVersion()).isEqualTo("question-local-answer-v1");
		assertThat(result.startedAt()).isEqualTo(STARTED_AT);
		assertThat(result.inputTokenCount()).isEqualTo(10);
		assertThat(result.outputTokenCount()).isEqualTo(5);
		assertThat(result.providerRequestId()).isEqualTo("request-1");
		assertThat(result.fallbackReason()).isNull();
	}

	@Test
	void callsGeminiExactlyOnceAfterPrimaryStrictOutputFailure() {
		CountingProvider primary = provider("bedrock", "amazon.nova-micro-v1:0", "```json\n" + validOutput() + "\n```", null);
		CountingProvider fallback = provider("gemini", "gemini-3.1-flash-lite", validOutput(), null);
		LocalAnswerGateway gateway = gateway(primary, fallback);

		GeneratedAnswer result = gateway.generate(prompt(), Duration.ofSeconds(30));

		assertThat(primary.calls()).isEqualTo(1);
		assertThat(fallback.calls()).isEqualTo(1);
		assertThat(result.provider()).isEqualTo("gemini");
		assertThat(result.model()).isEqualTo("gemini-3.1-flash-lite");
		assertThat(result.fallbackReason()).isEqualTo("primary_invalid_output");
	}

	@Test
	void callsGeminiExactlyOnceAfterPrimaryTimeout() {
		CountingProvider primary = provider(
			"bedrock", "amazon.nova-micro-v1:0", null, LocalAnswerProviderFailureCode.timeout
		);
		CountingProvider fallback = provider("gemini", "gemini-3.1-flash-lite", validOutput(), null);

		GeneratedAnswer result = gateway(primary, fallback).generate(prompt(), Duration.ofSeconds(30));

		assertThat(primary.calls()).isEqualTo(1);
		assertThat(fallback.calls()).isEqualTo(1);
		assertThat(result.fallbackReason()).isEqualTo("primary_timeout");
	}

	@Test
	void exposesOnlyFiniteFailureCodesWhenBothProvidersFail() {
		CountingProvider primary = provider(
			"bedrock", "amazon.nova-micro-v1:0", null, LocalAnswerProviderFailureCode.timeout
		);
		CountingProvider fallback = provider(
			"gemini", "gemini-3.1-flash-lite", null, LocalAnswerProviderFailureCode.provider_unavailable
		);

		assertThatThrownBy(() -> gateway(primary, fallback).generate(prompt(), Duration.ofSeconds(30)))
			.isInstanceOfSatisfying(QuestionGenerationUnavailableException.class, exception -> {
				assertThat(exception.primaryFailure()).isEqualTo(LocalAnswerProviderFailureCode.timeout);
				assertThat(exception.fallbackFailure()).isEqualTo(LocalAnswerProviderFailureCode.provider_unavailable);
				assertThat(exception).hasMessage("Local question answer generation is unavailable");
				assertThat(exception).hasMessageNotContaining("버스");
				assertThat(exception).hasMessageNotContaining("secret");
				assertThat(exception).hasMessageNotContaining("raw");
				assertThat(exception.getCause()).isNull();
			});
		assertThat(primary.calls()).isEqualTo(1);
		assertThat(fallback.calls()).isEqualTo(1);
	}

	@Test
	void requiresTheConfiguredThirtySecondBudget() {
		LocalAnswerGateway gateway = gateway(
			provider("bedrock", "amazon.nova-micro-v1:0", validOutput(), null),
			provider("gemini", "gemini-3.1-flash-lite", validOutput(), null)
		);

		assertThatThrownBy(() -> gateway.generate(prompt(), Duration.ofSeconds(31)))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("30 seconds");
	}

	private LocalAnswerGateway gateway(LocalAnswerProvider primary, LocalAnswerProvider fallback) {
		return new FallbackLocalAnswerGateway(
			primary,
			fallback,
			new LocalAnswerOutputParser(new ObjectMapper()),
			properties()
		);
	}

	private CountingProvider provider(
		String provider,
		String model,
		String output,
		LocalAnswerProviderFailureCode failureCode
	) {
		return new CountingProvider(provider, model, output, failureCode);
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
			LocalAnswerRegion.empty(),
			List.of(new LocalAnswerEvidence(0, "버스 안내", "앞문 승차, 뒷문 하차", "government"))
		);
	}

	private static String validOutput() {
		return "{\"answer\":\"앞문으로 타세요.\",\"citations\":[{\"evidenceIndex\":0,\"startIndex\":0,\"endIndex\":9}]}";
	}

	private static final class CountingProvider implements LocalAnswerProvider {

		private final String provider;
		private final String model;
		private final String output;
		private final LocalAnswerProviderFailureCode failureCode;
		private final AtomicInteger calls = new AtomicInteger();

		private CountingProvider(
			String provider,
			String model,
			String output,
			LocalAnswerProviderFailureCode failureCode
		) {
			this.provider = provider;
			this.model = model;
			this.output = output;
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
		public LocalAnswerProviderResponse generate(LocalAnswerPrompt prompt) {
			calls.incrementAndGet();
			if (failureCode != null) {
				throw new LocalAnswerProviderException(failureCode);
			}
			return new LocalAnswerProviderResponse(output, STARTED_AT, 10, 5, "request-1");
		}

		private int calls() {
			return calls.get();
		}
	}
}
