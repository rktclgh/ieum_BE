package shinhan.fibri.ieum.main.report.ai.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import shinhan.fibri.ieum.common.ai.report.dto.ReportReviewResponse;
import shinhan.fibri.ieum.main.report.repository.ReportAiReviewResult;

@Component
@ConditionalOnProperty(prefix = "app.ai.report", name = "enabled", havingValue = "true")
public class ReportAiReviewResultMapper {

	private static final Set<String> DECISIONS = Set.of("suspend", "hold", "normal");
	private static final Set<String> SEVERITIES = Set.of("low", "medium", "high", "critical");
	private static final Set<String> EVIDENCE_TYPES = Set.of("text", "image", "both");

	private final ObjectMapper objectMapper;

	public ReportAiReviewResultMapper(ObjectMapper objectMapper) {
		this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
	}

	public MappedReportAiReview map(ReportReviewResponse response, OffsetDateTime reviewedAt) {
		if (!valid(response, reviewedAt)) {
			throw invalid();
		}
		Duration sanctionDuration = sanctionDuration(response.decision(), response.severity());
		String recommendation = switch (response.decision()) {
			case "suspend" -> "temporary_suspend";
			case "hold" -> "hold";
			case "normal" -> "dismiss";
			default -> throw invalid();
		};
		try {
			ReportAiReviewResult result = new ReportAiReviewResult(
				response.decision(),
				recommendation,
				response.confidence().setScale(4, RoundingMode.HALF_UP),
				response.reason(),
				response.modelVersion(),
				response.promptVersion(),
				response.policySetHash(),
				reviewedAt,
				objectMapper.writeValueAsString(response)
			);
			return new MappedReportAiReview(result, sanctionDuration);
		} catch (JsonProcessingException | IllegalArgumentException exception) {
			throw invalid();
		}
	}

	private boolean valid(ReportReviewResponse response, OffsetDateTime reviewedAt) {
		if (response == null || reviewedAt == null || !DECISIONS.contains(response.decision())) {
			return false;
		}
		if (response.severity() != null && !SEVERITIES.contains(response.severity())) {
			return false;
		}
		if ("suspend".equals(response.decision())
				&& !("high".equals(response.severity()) || "critical".equals(response.severity()))) {
			return false;
		}
		if (!"normal".equals(response.decision()) && response.severity() == null) {
			return false;
		}
		BigDecimal confidence = response.confidence();
		boolean validCommonContract = confidence != null
			&& confidence.compareTo(BigDecimal.ZERO) >= 0
			&& confidence.compareTo(BigDecimal.ONE) <= 0
			&& hasText(response.reason())
			&& hasText(response.modelVersion())
			&& response.modelVersion().length() <= 120
			&& hasText(response.promptVersion())
			&& response.promptVersion().length() <= 80
			&& response.policySetHash() != null
			&& response.policySetHash().matches("[0-9a-f]{64}")
			&& isArray(response.evidence(), false)
			&& isArray(response.matchedRules(), false)
			&& response.policySnapshot() != null
			&& response.policySnapshot().isObject()
			&& response.fallbackUsed() != null
			&& isArray(response.providerAttempts(), true);
		return validCommonContract
			&& (!"suspend".equals(response.decision()) || validSuspendContract(response));
	}

	private boolean validSuspendContract(ReportReviewResponse response) {
		if (!hasText(response.category())
			|| !validEvidence(response.evidence())
			|| response.matchedRules().size() != 1) {
			return false;
		}
		JsonNode matchedRule = response.matchedRules().get(0);
		if (matchedRule == null || !matchedRule.isObject()) {
			return false;
		}
		String ruleCode = text(matchedRule, "ruleCode");
		JsonNode revisionNode = matchedRule.get("revision");
		if (!hasText(ruleCode) || !positiveInteger(revisionNode)) {
			return false;
		}

		JsonNode snapshot = response.policySnapshot();
		if (!response.policySetHash().equals(text(snapshot, "policySetHash"))) {
			return false;
		}
		JsonNode rules = snapshot.get("rules");
		if (rules == null || !rules.isArray()) {
			return false;
		}
		JsonNode snapshotRule = null;
		for (JsonNode rule : rules) {
			if (rule != null
				&& rule.isObject()
				&& ruleCode.equals(text(rule, "ruleCode"))
				&& revisionNode.asInt() == integer(rule.get("revision"))) {
				if (snapshotRule != null) {
					return false;
				}
				snapshotRule = rule;
			}
		}
		if (snapshotRule == null
			|| !"suspend".equals(text(snapshotRule, "decision"))
			|| !response.severity().equals(text(snapshotRule, "severity"))
			|| !response.category().equals(text(snapshotRule, "category"))) {
			return false;
		}
		JsonNode minConfidence = snapshotRule.get("minConfidence");
		return minConfidence != null
			&& minConfidence.isNumber()
			&& minConfidence.decimalValue().compareTo(BigDecimal.ZERO) >= 0
			&& minConfidence.decimalValue().compareTo(BigDecimal.ONE) <= 0
			&& response.confidence().compareTo(minConfidence.decimalValue()) >= 0;
	}

	private boolean validEvidence(JsonNode evidence) {
		Set<Long> messageIds = new HashSet<>();
		for (JsonNode item : evidence) {
			JsonNode messageId = item == null ? null : item.get("messageId");
			String type = item == null ? null : text(item, "type");
			if (item == null
				|| !item.isObject()
				|| messageId == null
				|| !messageId.isIntegralNumber()
				|| !messageId.canConvertToLong()
				|| messageId.asLong() < 1
				|| !messageIds.add(messageId.asLong())
				|| !EVIDENCE_TYPES.contains(type)) {
				return false;
			}
		}
		return !messageIds.isEmpty();
	}

	private String text(JsonNode object, String field) {
		JsonNode value = object == null ? null : object.get(field);
		return value != null && value.isTextual() ? value.textValue() : null;
	}

	private boolean positiveInteger(JsonNode value) {
		return value != null && value.isIntegralNumber() && value.canConvertToInt() && value.asInt() > 0;
	}

	private int integer(JsonNode value) {
		return positiveInteger(value) ? value.asInt() : -1;
	}

	private Duration sanctionDuration(String decision, String severity) {
		if (!"suspend".equals(decision)) {
			return null;
		}
		return "critical".equals(severity) ? Duration.ofDays(7) : Duration.ofHours(72);
	}

	private boolean isArray(JsonNode node, boolean requireItem) {
		return node != null && node.isArray() && (!requireItem || !node.isEmpty());
	}

	private boolean hasText(String value) {
		return value != null && !value.isBlank();
	}

	private ReportAiPermanentException invalid() {
		return new ReportAiPermanentException("REPORT_AI_RESPONSE_INVALID");
	}
}
