package shinhan.fibri.ieum.ai.report.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import shinhan.fibri.ieum.ai.report.domain.ReportModelReviewOutput;
import shinhan.fibri.ieum.ai.report.domain.ReportModelRuleMatch;

public class ReportReviewModelOutputParser {

	private static final Set<String> ROOT_FIELDS = Set.of("matchedRules", "uncertain");
	private static final Set<String> MATCH_FIELDS = Set.of("ruleCode", "confidence", "evidenceMessageIds", "reason");

	private final ObjectReader strictReader;

	public ReportReviewModelOutputParser() {
		this(new ObjectMapper());
	}

	public ReportReviewModelOutputParser(ObjectMapper objectMapper) {
		this.strictReader = Objects.requireNonNull(objectMapper, "objectMapper must not be null")
			.reader()
			.with(StreamReadFeature.STRICT_DUPLICATE_DETECTION);
	}

	public ReportModelReviewOutput parse(String rawOutput) {
		if (rawOutput == null || rawOutput.isBlank()) {
			throw invalid();
		}
		try {
			JsonNode root = strictReader.readTree(rawOutput);
			if (root == null || !root.isObject() || !hasExactlyFields(root, ROOT_FIELDS)) {
				throw invalid();
			}
			JsonNode matchedRules = root.get("matchedRules");
			JsonNode uncertain = root.get("uncertain");
			if (!matchedRules.isArray() || !uncertain.isBoolean()) {
				throw invalid();
			}

			List<ReportModelRuleMatch> matches = new ArrayList<>();
			for (JsonNode match : matchedRules) {
				matches.add(parseMatch(match));
			}
			return new ReportModelReviewOutput(matches, uncertain.booleanValue());
		} catch (JsonProcessingException | IllegalArgumentException exception) {
			if (exception instanceof InvalidReportModelOutputException invalidOutput) {
				throw invalidOutput;
			}
			throw invalid();
		}
	}

	private ReportModelRuleMatch parseMatch(JsonNode match) {
		if (!match.isObject() || !hasExactlyFields(match, MATCH_FIELDS)) {
			throw invalid();
		}
		JsonNode ruleCode = match.get("ruleCode");
		JsonNode confidence = match.get("confidence");
		JsonNode evidenceMessageIds = match.get("evidenceMessageIds");
		JsonNode reason = match.get("reason");
		if (!ruleCode.isTextual() || !confidence.isNumber() || !evidenceMessageIds.isArray() || !reason.isTextual()) {
			throw invalid();
		}
		return new ReportModelRuleMatch(
			ruleCode.textValue(),
			decimal(confidence),
			messageIds(evidenceMessageIds),
			reason.textValue()
		);
	}

	private BigDecimal decimal(JsonNode confidence) {
		try {
			return confidence.decimalValue();
		} catch (RuntimeException exception) {
			throw invalid();
		}
	}

	private List<Long> messageIds(JsonNode evidenceMessageIds) {
		List<Long> ids = new ArrayList<>();
		for (JsonNode messageId : evidenceMessageIds) {
			if (!messageId.isIntegralNumber() || !messageId.canConvertToLong()) {
				throw invalid();
			}
			ids.add(messageId.longValue());
		}
		return ids;
	}

	private boolean hasExactlyFields(JsonNode node, Set<String> expectedFields) {
		Set<String> fields = new HashSet<>();
		node.fieldNames().forEachRemaining(fields::add);
		return fields.equals(expectedFields);
	}

	private InvalidReportModelOutputException invalid() {
		return new InvalidReportModelOutputException("Model output must match the report review schema");
	}
}
