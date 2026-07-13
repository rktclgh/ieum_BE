package shinhan.fibri.ieum.ai.question.finalization;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

final class QuestionAnswerEvidenceValidator {

	private static final String KNOWLEDGE_CHUNK = "knowledge_chunk";
	private static final String KG_RELATION = "kg_relation";
	private static final String WEB = "web";
	private static final Set<String> TYPES = Set.of(KNOWLEDGE_CHUNK, KG_RELATION, WEB);
	private static final Set<String> ALLOWED_FIELDS = Set.of(
		"type",
		"sourceId",
		"chunkId",
		"relationId",
		"sourceType",
		"title",
		"excerpt",
		"url",
		"domain",
		"contentHash",
		"score",
		"startIndex",
		"endIndex",
		"retrievedAt"
	);
	private static final Pattern SHA_256 = Pattern.compile("[0-9a-fA-F]{64}");

	private QuestionAnswerEvidenceValidator() {
	}

	static JsonNode validateAndCopy(JsonNode evidence) {
		if (evidence == null || !evidence.isObject()) {
			throw invalid("evidence must be a JSON object");
		}
		evidence.fieldNames().forEachRemaining(field -> {
			if (!ALLOWED_FIELDS.contains(field)) {
				throw invalid("unsupported evidence field: " + field);
			}
		});
		String type = requiredText(evidence, "type");
		if (!TYPES.contains(type)) {
			throw invalid("unsupported evidence type");
		}

		requiredText(evidence, "title");
		requiredText(evidence, "excerpt");
		String contentHash = requiredText(evidence, "contentHash");
		if (!SHA_256.matcher(contentHash).matches()) {
			throw invalid("contentHash must be a SHA-256 hex digest");
		}
		validateScore(evidence.get("score"));
		validateRetrievedAt(evidence.get("retrievedAt"));
		validateOptionalPositiveLong(evidence, "sourceId");
		validateOptionalPositiveLong(evidence, "chunkId");
		validateOptionalPositiveLong(evidence, "relationId");
		validateOptionalText(evidence, "sourceType");
		validateOptionalText(evidence, "domain");
		JsonNode optionalUrl = evidence.get("url");
		if (optionalUrl != null && !optionalUrl.isNull()) {
			if (!optionalUrl.isTextual() || optionalUrl.textValue().isBlank()) {
				throw invalid("evidence URL must be nonblank text when present");
			}
			validateWebUrl(optionalUrl.textValue());
		}

		switch (type) {
			case KNOWLEDGE_CHUNK -> {
				requiredPositiveLong(evidence, "sourceId");
				requiredPositiveLong(evidence, "chunkId");
				requiredText(evidence, "sourceType");
			}
			case KG_RELATION -> {
				requiredPositiveLong(evidence, "sourceId");
				requiredPositiveLong(evidence, "chunkId");
				requiredPositiveLong(evidence, "relationId");
				requiredText(evidence, "sourceType");
			}
			case WEB -> {
				validateWebUrl(requiredText(evidence, "url"));
				requiredText(evidence, "domain");
			}
			default -> throw invalid("unsupported evidence type");
		}

		validateAnnotationShape(evidence, WEB.equals(type));
		return evidence.deepCopy();
	}

	static void validateForAnswer(
		QuestionAnswerMode answerMode,
		String content,
		List<JsonNode> evidence
	) {
		int webEvidenceCount = 0;
		int citationCount = 0;
		Set<CitationRange> citationRanges = new HashSet<>();
		for (JsonNode item : evidence) {
			String type = item.get("type").textValue();
			if (WEB.equals(type)) {
				webEvidenceCount++;
				if (answerMode == QuestionAnswerMode.LOCAL_GROUNDED) {
					throw invalid("local grounded answers cannot contain web evidence");
				}
			}
			CitationRange citationRange = validateAnnotationRange(item, content);
			if (citationRange != null) {
				citationCount++;
				if (!citationRanges.add(citationRange)) {
					throw invalid("each citation range must be unique");
				}
			}
		}
		if (citationCount == 0) {
			throw invalid("grounded answers require at least one citation");
		}
		if (answerMode == QuestionAnswerMode.WEB_GROUNDED && webEvidenceCount == 0) {
			throw invalid("web grounded answers require at least one web citation");
		}
	}

	private static String requiredText(JsonNode evidence, String field) {
		JsonNode value = evidence.get(field);
		if (value == null || !value.isTextual() || value.textValue().isBlank()) {
			throw invalid(field + " must be nonblank text");
		}
		return value.textValue();
	}

	private static long requiredPositiveLong(JsonNode evidence, String field) {
		JsonNode value = evidence.get(field);
		if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
			throw invalid(field + " must be a positive integer");
		}
		long number = value.longValue();
		if (number < 1) {
			throw invalid(field + " must be a positive integer");
		}
		return number;
	}

	private static void validateOptionalPositiveLong(JsonNode evidence, String field) {
		JsonNode value = evidence.get(field);
		if (value != null && !value.isNull()) {
			requiredPositiveLong(evidence, field);
		}
	}

	private static void validateOptionalText(JsonNode evidence, String field) {
		JsonNode value = evidence.get(field);
		if (value != null && !value.isNull()) {
			requiredText(evidence, field);
		}
	}

	private static void validateScore(JsonNode score) {
		if (score == null || !score.isNumber() || !Double.isFinite(score.doubleValue())) {
			throw invalid("score must be a finite number between 0 and 1");
		}
		BigDecimal value;
		try {
			value = score.decimalValue();
		} catch (ArithmeticException exception) {
			throw invalid("score must be a finite number between 0 and 1");
		}
		if (value.compareTo(BigDecimal.ZERO) < 0 || value.compareTo(BigDecimal.ONE) > 0) {
			throw invalid("score must be a finite number between 0 and 1");
		}
	}

	private static void validateRetrievedAt(JsonNode retrievedAt) {
		if (retrievedAt == null || !retrievedAt.isTextual()) {
			throw invalid("retrievedAt must be an ISO-8601 instant");
		}
		try {
			Instant.parse(retrievedAt.textValue());
		} catch (DateTimeParseException exception) {
			throw invalid("retrievedAt must be an ISO-8601 instant");
		}
	}

	private static void validateWebUrl(String rawUrl) {
		try {
			URI url = new URI(rawUrl);
			String scheme = url.getScheme();
			if (scheme == null
				|| !("http".equals(scheme.toLowerCase(Locale.ROOT))
				|| "https".equals(scheme.toLowerCase(Locale.ROOT)))
				|| url.getHost() == null
				|| url.getUserInfo() != null) {
				throw invalid("web evidence URL must be HTTP(S) without userinfo");
			}
		} catch (URISyntaxException exception) {
			throw invalid("web evidence URL must be HTTP(S) without userinfo");
		}
	}

	private static void validateAnnotationShape(JsonNode evidence, boolean required) {
		JsonNode start = evidence.get("startIndex");
		JsonNode end = evidence.get("endIndex");
		boolean startPresent = start != null && !start.isNull();
		boolean endPresent = end != null && !end.isNull();
		if (!startPresent && !endPresent && !required) {
			return;
		}
		if (!startPresent || !endPresent
			|| !start.isIntegralNumber() || !start.canConvertToInt()
			|| !end.isIntegralNumber() || !end.canConvertToInt()) {
			throw invalid("citation startIndex and endIndex must be integer pairs");
		}
		if (start.intValue() < 0 || end.intValue() <= start.intValue()) {
			throw invalid("citation indices must define a nonempty range");
		}
	}

	private static CitationRange validateAnnotationRange(JsonNode evidence, String content) {
		JsonNode start = evidence.get("startIndex");
		JsonNode end = evidence.get("endIndex");
		if (start == null || start.isNull()) {
			return null;
		}
		int startIndex = start.intValue();
		int endIndex = end.intValue();
		if (startIndex < 0 || endIndex <= startIndex || endIndex > content.length()) {
			throw invalid("citation indices must be inside answer content");
		}
		if (splitsSurrogatePair(content, startIndex) || splitsSurrogatePair(content, endIndex)) {
			throw invalid("citation indices must not split a UTF-16 surrogate pair");
		}
		return new CitationRange(startIndex, endIndex);
	}

	private static boolean splitsSurrogatePair(String content, int index) {
		return index > 0
			&& index < content.length()
			&& Character.isHighSurrogate(content.charAt(index - 1))
			&& Character.isLowSurrogate(content.charAt(index));
	}

	private static IllegalArgumentException invalid(String message) {
		return new IllegalArgumentException(message);
	}

	private record CitationRange(int startIndex, int endIndex) {
	}
}
