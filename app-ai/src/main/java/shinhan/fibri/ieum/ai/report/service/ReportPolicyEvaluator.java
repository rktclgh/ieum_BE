package shinhan.fibri.ieum.ai.report.service;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import shinhan.fibri.ieum.ai.report.domain.ReportEvidenceType;
import shinhan.fibri.ieum.ai.report.domain.ReportModelReviewOutput;
import shinhan.fibri.ieum.ai.report.domain.ReportModelRuleMatch;
import shinhan.fibri.ieum.ai.report.domain.ReportPolicyDecision;
import shinhan.fibri.ieum.ai.report.domain.ReportPolicyRule;
import shinhan.fibri.ieum.ai.report.domain.ReportPolicySeverity;
import shinhan.fibri.ieum.ai.report.domain.ReportPolicySnapshot;
import shinhan.fibri.ieum.ai.report.domain.ReportReviewEvidenceMessage;

@Service
public class ReportPolicyEvaluator {

	private static final BigDecimal SYSTEM_HOLD_CONFIDENCE = new BigDecimal("0.0000");
	private static final String SYSTEM_HOLD_CATEGORY = "model_uncertain";
	private static final String SYSTEM_HOLD_REASON = "Model uncertainty requires manual review";
	private static final String NO_MATCH_REASON = "No policy rule matched";
	private static final Comparator<PolicyCandidate> CANDIDATE_ORDER = Comparator
		.comparingInt((PolicyCandidate candidate) -> candidate.rule().severity().ordinal()).reversed()
		.thenComparing(Comparator.comparingInt((PolicyCandidate candidate) -> candidate.rule().priority()).reversed())
		.thenComparing(candidate -> candidate.rule().ruleCode());

	public ReportPolicyEvaluationResult evaluate(
		ReportPolicySnapshot snapshot,
		ReportModelReviewOutput modelOutput,
		List<ReportReviewEvidenceMessage> reviewContext
	) {
		Map<String, ReportPolicyRule> rulesByCode = snapshot.rules().stream()
			.collect(Collectors.toUnmodifiableMap(ReportPolicyRule::ruleCode, rule -> rule));
		Map<Long, ReportReviewEvidenceMessage> messagesById = indexMessages(reviewContext);
		List<PolicyCandidate> candidates = modelOutput.matchedRules().stream()
			.map(match -> toCandidate(rulesByCode, messagesById, match))
			.toList();

		PolicyCandidate qualifiedSuspend = select(candidates, candidate ->
			candidate.rule().decision() == ReportPolicyDecision.suspend && candidate.qualifies());
		if (qualifiedSuspend != null) {
			return fromRule(ReportPolicyDecision.suspend, qualifiedSuspend);
		}

		PolicyCandidate qualifiedHold = select(candidates, candidate ->
			candidate.rule().decision() == ReportPolicyDecision.hold && candidate.qualifies());
		if (qualifiedHold != null) {
			return fromRule(ReportPolicyDecision.hold, qualifiedHold);
		}

		PolicyCandidate subthresholdPotential = select(candidates, candidate ->
			(candidate.rule().decision() == ReportPolicyDecision.suspend || candidate.rule().decision() == ReportPolicyDecision.hold)
				&& !candidate.qualifies());
		if (subthresholdPotential != null) {
			return fromRule(ReportPolicyDecision.hold, subthresholdPotential);
		}

		if (modelOutput.uncertain()) {
			return new ReportPolicyEvaluationResult(
				ReportPolicyDecision.hold,
				SYSTEM_HOLD_CATEGORY,
				ReportPolicySeverity.low,
				SYSTEM_HOLD_CONFIDENCE,
				SYSTEM_HOLD_REASON,
				null,
				List.of(),
				List.of()
			);
		}

		PolicyCandidate qualifiedNormal = select(candidates, candidate ->
			candidate.rule().decision() == ReportPolicyDecision.normal && candidate.qualifies());
		if (qualifiedNormal != null) {
			return fromRule(ReportPolicyDecision.normal, qualifiedNormal);
		}

		return new ReportPolicyEvaluationResult(
			ReportPolicyDecision.normal,
			null,
			null,
			BigDecimal.ZERO,
			NO_MATCH_REASON,
			null,
			List.of(),
			List.of()
		);
	}

	private PolicyCandidate toCandidate(
		Map<String, ReportPolicyRule> rulesByCode,
		Map<Long, ReportReviewEvidenceMessage> messagesById,
		ReportModelRuleMatch match
	) {
		ReportPolicyRule rule = rulesByCode.get(match.ruleCode());
		if (rule == null) {
			throw new InvalidReportModelOutputException("Unknown policy rule: " + match.ruleCode());
		}
		validateEvidence(rule, match, messagesById);
		return new PolicyCandidate(rule, match, match.confidence().compareTo(rule.minConfidence()) >= 0);
	}

	private Map<Long, ReportReviewEvidenceMessage> indexMessages(List<ReportReviewEvidenceMessage> reviewContext) {
		if (reviewContext == null) {
			throw new IllegalArgumentException("reviewContext must not be null");
		}
		Map<Long, ReportReviewEvidenceMessage> messagesById = new HashMap<>();
		for (ReportReviewEvidenceMessage message : reviewContext) {
			if (message == null || messagesById.putIfAbsent(message.messageId(), message) != null) {
				throw new IllegalArgumentException("reviewContext messageId must be unique");
			}
		}
		return Map.copyOf(messagesById);
	}

	private void validateEvidence(
		ReportPolicyRule rule,
		ReportModelRuleMatch match,
		Map<Long, ReportReviewEvidenceMessage> messagesById
	) {
		List<ReportReviewEvidenceMessage> citedMessages = match.evidenceMessageIds().stream()
			.map(messageId -> requireMessage(messagesById, messageId))
			.toList();
		if (!supportsEvidenceType(rule.evidenceType(), citedMessages)) {
			throw new InvalidReportModelOutputException("Cited messages do not satisfy " + rule.evidenceType() + " evidence");
		}
		if (rule.decision() == ReportPolicyDecision.suspend) {
			List<ReportReviewEvidenceMessage> reportedUserMessages = citedMessages.stream()
				.filter(message -> "reported_user".equals(message.actor()))
				.toList();
			if (!supportsEvidenceType(rule.evidenceType(), reportedUserMessages)) {
				throw new InvalidReportModelOutputException("Suspend evidence must be satisfied by reported_user messages");
			}
		}
	}

	private ReportReviewEvidenceMessage requireMessage(
		Map<Long, ReportReviewEvidenceMessage> messagesById,
		Long messageId
	) {
		ReportReviewEvidenceMessage message = messagesById.get(messageId);
		if (message == null) {
			throw new InvalidReportModelOutputException("Unknown evidence message: " + messageId);
		}
		return message;
	}

	private boolean supportsEvidenceType(ReportEvidenceType evidenceType, List<ReportReviewEvidenceMessage> messages) {
		if (messages.isEmpty()) {
			return false;
		}
		return switch (evidenceType) {
			case text -> messages.stream().allMatch(ReportReviewEvidenceMessage::hasText);
			case image -> messages.stream().allMatch(ReportReviewEvidenceMessage::verifiedImage);
			case both -> messages.stream().anyMatch(ReportReviewEvidenceMessage::hasText)
				&& messages.stream().anyMatch(ReportReviewEvidenceMessage::verifiedImage);
		};
	}

	private PolicyCandidate select(List<PolicyCandidate> candidates, Predicate<PolicyCandidate> predicate) {
		return candidates.stream().filter(predicate).min(CANDIDATE_ORDER).orElse(null);
	}

	private ReportPolicyEvaluationResult fromRule(ReportPolicyDecision decision, PolicyCandidate candidate) {
		return new ReportPolicyEvaluationResult(
			decision,
			candidate.rule().category(),
			candidate.rule().severity(),
			candidate.match().confidence(),
			candidate.match().reason(),
			candidate.rule().ruleCode(),
			candidate.match().evidenceMessageIds(),
			List.of(new ReportPolicyMatchedRule(candidate.rule().ruleCode(), candidate.rule().revision()))
		);
	}

	private record PolicyCandidate(ReportPolicyRule rule, ReportModelRuleMatch match, boolean qualifies) {
	}
}
