package shinhan.fibri.ieum.ai.report.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import shinhan.fibri.ieum.ai.report.domain.ReportEvidenceType;
import shinhan.fibri.ieum.ai.report.domain.ReportModelReviewOutput;
import shinhan.fibri.ieum.ai.report.domain.ReportPolicyDecision;
import shinhan.fibri.ieum.ai.report.domain.ReportPolicyRule;
import shinhan.fibri.ieum.ai.report.domain.ReportPolicySeverity;
import shinhan.fibri.ieum.ai.report.domain.ReportPolicySnapshot;
import shinhan.fibri.ieum.ai.report.domain.ReportReviewEvidenceMessage;

class GeminiReportReviewModelProviderTest {

	private static final String MODEL = "gemini-3.1-flash-lite";

	@Test
	void sendsJsonOnlyPromptWithoutSearchGroundingAndWithInlineWebpImages() {
		FakeGeminiReportReviewClient client = new FakeGeminiReportReviewClient("""
			{"matchedRules":[{"ruleCode":"TEXT-SPAM-001","confidence":0.91,"evidenceMessageIds":[8],"reason":"반복적인 스팸 메시지입니다"}],"uncertain":false}
			""");
		GeminiReportReviewModelProvider provider = provider(client);

		ReportModelReviewOutput output = provider.review(preparedReview(), policySnapshot());

		assertThat(provider.provider()).isEqualTo("gemini");
		assertThat(provider.model()).isEqualTo(MODEL);
		assertThat(output.uncertain()).isFalse();
		assertThat(output.matchedRules()).hasSize(1);
		assertThat(client.request.model()).isEqualTo(MODEL);
		assertThat(client.request.responseMimeType()).isEqualTo("application/json");
		assertThat(client.request.googleSearchGroundingEnabled()).isFalse();
		assertThat(client.request.systemInstruction()).contains("Return JSON only");
		assertThat(client.request.userInstruction())
			.contains("\"reportId\":42")
			.contains("\"policySetHash\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\"");
		assertThat(client.request.images()).extracting(ReportReviewModelPromptImage::messageId).containsExactly(3L, 8L);
		assertThat(client.request.images().getFirst().contentType()).isEqualTo("image/webp");
		assertThat(client.request.images().getFirst().bytes()).containsExactly('R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P');
	}

	@Test
	void leavesMalformedModelOutputForFallbackParserHandling() {
		GeminiReportReviewModelProvider provider = provider(new FakeGeminiReportReviewClient("not-json"));

		assertThatThrownBy(() -> provider.review(preparedReview(), policySnapshot()))
			.isInstanceOf(InvalidReportModelOutputException.class);
	}

	@ParameterizedTest
	@EnumSource(ReportReviewProviderErrorCode.class)
	void mapsClientFailuresToProviderFailures(ReportReviewProviderErrorCode errorCode) {
		GeminiReportReviewModelProvider provider = provider(new FakeGeminiReportReviewClient(errorCode));

		assertThatThrownBy(() -> provider.review(preparedReview(), policySnapshot()))
			.isInstanceOfSatisfying(ReportReviewModelProviderException.class, exception ->
				assertThat(exception.errorCode()).isEqualTo(errorCode)
			);
	}

	@Test
	void mapsUnexpectedClientRuntimeFailuresToTransportErrors() {
		GeminiReportReviewModelProvider provider = new GeminiReportReviewModelProvider(
			MODEL,
			request -> {
				throw new IllegalStateException("raw Gemini SDK detail must not escape");
			},
			new ReportReviewModelPromptFactory(),
			new ReportReviewModelOutputParser()
		);

		assertThatThrownBy(() -> provider.review(preparedReview(), policySnapshot()))
			.isInstanceOfSatisfying(ReportReviewModelProviderException.class, exception -> {
				assertThat(exception.errorCode()).isEqualTo(ReportReviewProviderErrorCode.transport_error);
				assertThat(exception).hasMessageNotContaining("raw Gemini SDK detail");
			});
	}

	private GeminiReportReviewModelProvider provider(FakeGeminiReportReviewClient client) {
		return new GeminiReportReviewModelProvider(
			MODEL,
			client,
			new ReportReviewModelPromptFactory(),
			new ReportReviewModelOutputParser()
		);
	}

	private PreparedReportReview preparedReview() {
		Map<Long, VerifiedReportEvidenceImage> images = new LinkedHashMap<>();
		images.put(8L, webpBytes());
		images.put(3L, webpBytes());
		return new PreparedReportReview(
			42L,
			UUID.fromString("019f458a-a38c-71b1-8f42-d2974189f6af"),
			8L,
			"spam",
			"repeated message",
			"b".repeat(64),
			List.of(
				new ReportReviewEvidenceMessage(8L, "reported_user", "spam message", true),
				new ReportReviewEvidenceMessage(3L, "reporter", "context", true)
			),
			new ReportEvidenceImageBatch(images, 24L)
		);
	}

	private ReportPolicySnapshot policySnapshot() {
		return new ReportPolicySnapshot(
			"a".repeat(64),
			List.of(new ReportPolicyRule(
				"TEXT-SPAM-001",
				"Spam policy",
				"spam",
				"Repeated unsolicited promotional messages",
				ReportPolicyDecision.hold,
				ReportPolicySeverity.low,
				new BigDecimal("0.80"),
				ReportEvidenceType.text,
				10,
				1,
				List.of(),
				List.of()
			))
		);
	}

	private VerifiedReportEvidenceImage webpBytes() {
		return new VerifiedReportEvidenceImage("image/webp", new byte[] {
			'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P'
		});
	}

	private static final class FakeGeminiReportReviewClient implements GeminiReportReviewClient {

		private final String output;
		private final ReportReviewProviderErrorCode errorCode;
		private GeminiReportReviewRequest request;

		private FakeGeminiReportReviewClient(String output) {
			this.output = output;
			this.errorCode = null;
		}

		private FakeGeminiReportReviewClient(ReportReviewProviderErrorCode errorCode) {
			this.output = null;
			this.errorCode = errorCode;
		}

		@Override
		public String generate(GeminiReportReviewRequest request) {
			this.request = request;
			if (errorCode != null) {
				throw new GeminiReportReviewClientException(errorCode);
			}
			return output;
		}
	}
}
