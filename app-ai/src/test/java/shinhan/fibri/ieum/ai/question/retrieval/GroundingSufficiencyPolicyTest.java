package shinhan.fibri.ieum.ai.question.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import shinhan.fibri.ieum.ai.question.analysis.QueryAnalysis;

class GroundingSufficiencyPolicyTest {

	private final GroundingSufficiencyPolicy policy = new GroundingSufficiencyPolicy();

	@Test
	void emptyEvidenceIsInsufficientForEveryRiskLevel() {
		assertThat(policy.evaluate(List.of(), false)).isEqualTo(new GroundingSufficiencyResult(
			GroundingSufficiencyResult.Decision.INSUFFICIENT,
			GroundingSufficiencyResult.Reason.EMPTY_EVIDENCE
		));
		assertThat(policy.evaluate(List.of(), true)).isEqualTo(new GroundingSufficiencyResult(
			GroundingSufficiencyResult.Decision.INSUFFICIENT,
			GroundingSufficiencyResult.Reason.EMPTY_EVIDENCE
		));
	}

	@Test
	void nonEmptyLowRiskEvidenceIsStructurallySufficientWithoutScoreThreshold() {
		VectorKnowledgeEvidence lowScore = evidence("community", "0.000000");

		assertThat(policy.evaluate(List.of(lowScore), false)).isEqualTo(new GroundingSufficiencyResult(
			GroundingSufficiencyResult.Decision.SUFFICIENT,
			GroundingSufficiencyResult.Reason.NON_EMPTY_LOW_RISK_EVIDENCE
		));
	}

	@Test
	void highRiskRequiresAnExactCaseNormalizedAuthorityGrade() {
		assertThat(policy.evaluate(List.of(evidence("community", "0.900000")), true))
			.isEqualTo(new GroundingSufficiencyResult(
				GroundingSufficiencyResult.Decision.INSUFFICIENT,
				GroundingSufficiencyResult.Reason.HIGH_RISK_AUTHORITY_EVIDENCE_MISSING
			));
		assertThat(policy.evaluate(List.of(evidence("government_agency", "0.900000")), true))
			.isEqualTo(new GroundingSufficiencyResult(
				GroundingSufficiencyResult.Decision.INSUFFICIENT,
				GroundingSufficiencyResult.Reason.HIGH_RISK_AUTHORITY_EVIDENCE_MISSING
			));
		assertThat(policy.evaluate(List.of(evidence(" GOVERNMENT ", "0.000000")), true))
			.isEqualTo(new GroundingSufficiencyResult(
				GroundingSufficiencyResult.Decision.SUFFICIENT,
				GroundingSufficiencyResult.Reason.HIGH_RISK_AUTHORITY_EVIDENCE_PRESENT
			));
		assertThat(policy.evaluate(List.of(evidence("Public_Agency", "0.000000")), true))
			.isEqualTo(new GroundingSufficiencyResult(
				GroundingSufficiencyResult.Decision.SUFFICIENT,
				GroundingSufficiencyResult.Reason.HIGH_RISK_AUTHORITY_EVIDENCE_PRESENT
			));
		assertThat(policy.evaluate(List.of(evidence(
			"verified_external",
			"public_agency",
			"0.000000"
		)), true)).isEqualTo(new GroundingSufficiencyResult(
			GroundingSufficiencyResult.Decision.SUFFICIENT,
			GroundingSufficiencyResult.Reason.HIGH_RISK_AUTHORITY_EVIDENCE_PRESENT
		));
	}

	@Test
	void highRiskRejectsHumanAnswerEvenWhenItsFreeFormMetadataClaimsGovernmentGrade() {
		VectorKnowledgeEvidence acceptedHumanAnswer = evidence(
			"accepted_human_answer",
			"government",
			"0.900000"
		);

		assertThat(policy.evaluate(List.of(acceptedHumanAnswer), true))
			.isEqualTo(new GroundingSufficiencyResult(
				GroundingSufficiencyResult.Decision.INSUFFICIENT,
				GroundingSufficiencyResult.Reason.HIGH_RISK_AUTHORITY_EVIDENCE_MISSING
			));
	}

	@Test
	void highRiskAcceptsAuthorityBackedHybridRelationEvidence() {
		assertThat(policy.evaluate(List.of(hybridEvidence()), true))
			.isEqualTo(new GroundingSufficiencyResult(
				GroundingSufficiencyResult.Decision.SUFFICIENT,
				GroundingSufficiencyResult.Reason.HIGH_RISK_AUTHORITY_EVIDENCE_PRESENT
			));
	}

	@Test
	void neutralQueryAnalysisFailsSafeThroughTheSameHighRiskAuthorityRule() {
		QueryAnalysis neutral = QueryAnalysis.neutral("question-query-analysis-v1");
		VectorKnowledgeEvidence acceptedHumanAnswer = evidence(
			"accepted_human_answer",
			"public_agency",
			"0.900000"
		);

		assertThat(neutral.highRiskDomain()).isTrue();
		assertThat(policy.evaluate(List.of(acceptedHumanAnswer), neutral.highRiskDomain()).decision())
			.isEqualTo(GroundingSufficiencyResult.Decision.INSUFFICIENT);
	}

	private VectorKnowledgeEvidence evidence(String sourceGrade, String score) {
		return evidence("curated", sourceGrade, score);
	}

	private VectorKnowledgeEvidence evidence(String sourceType, String sourceGrade, String score) {
		BigDecimal value = new BigDecimal(score);
		return new VectorKnowledgeEvidence(
			1L,
			1L,
			sourceType,
			"title",
			"excerpt",
			sourceGrade,
			"a".repeat(64),
			"https://example.com/source",
			"transportation",
			"community",
			GeoScope.general,
			value,
			value,
			value,
			value,
			null,
			Instant.parse("2026-07-13T03:04:05Z")
		);
	}

	private HybridKnowledgeEvidence hybridEvidence() {
		return new HybridKnowledgeEvidence(
			1L,
			1L,
			"curated",
			"title",
			"관계: 서비스 supports 가정\n근거: excerpt",
			"government",
			"b".repeat(64),
			"https://example.com/source",
			"welfare",
			"welfare",
			GeoScope.general,
			new BigDecimal("0.90"),
			1,
			1,
			10L,
			"서비스",
			"subject",
			"서비스",
			"supports",
			"가정",
			new BigDecimal("0.95"),
			new BigDecimal("0.90"),
			BigDecimal.ZERO,
			new BigDecimal("0.90"),
			null,
			Instant.parse("2026-07-13T03:04:05Z")
		);
	}
}
