package shinhan.fibri.ieum.ai.question.grounding;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

final class GroundingValidationOutputParser {

	private static final Set<String> ROOT_FIELDS = Set.of("supported", "score", "unsupportedClaims");

	private final ObjectReader strictReader;

	GroundingValidationOutputParser(ObjectMapper objectMapper) {
		Objects.requireNonNull(objectMapper, "objectMapper must not be null");
		this.strictReader = objectMapper.readerFor(JsonNode.class).with(
			DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
			DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY
		);
	}

	GroundingValidation parse(String rawOutput) {
		if (rawOutput == null || rawOutput.isBlank()) {
			throw invalid();
		}
		try {
			JsonNode root = strictReader.readTree(rawOutput);
			requireExactObject(root);
			return new GroundingValidation(
				requiredBoolean(root.get("supported")),
				requiredScore(root.get("score")),
				claims(root.get("unsupportedClaims"))
			);
		}
		catch (InvalidGroundingValidationOutputException exception) {
			throw exception;
		}
		catch (JsonProcessingException | RuntimeException exception) {
			throw invalid();
		}
	}

	private void requireExactObject(JsonNode node) {
		if (node == null || !node.isObject()) {
			throw invalid();
		}
		Set<String> actualFields = new HashSet<>();
		node.fieldNames().forEachRemaining(actualFields::add);
		if (!actualFields.equals(ROOT_FIELDS)) {
			throw invalid();
		}
	}

	private boolean requiredBoolean(JsonNode node) {
		if (node == null || !node.isBoolean()) {
			throw invalid();
		}
		return node.booleanValue();
	}

	private BigDecimal requiredScore(JsonNode node) {
		if (node == null || !node.isNumber()) {
			throw invalid();
		}
		return node.decimalValue();
	}

	private List<String> claims(JsonNode node) {
		if (node == null || !node.isArray()) {
			throw invalid();
		}
		List<String> claims = new ArrayList<>(node.size());
		for (JsonNode claim : node) {
			if (!claim.isTextual()) {
				throw invalid();
			}
			claims.add(claim.textValue());
		}
		return claims;
	}

	private InvalidGroundingValidationOutputException invalid() {
		return new InvalidGroundingValidationOutputException();
	}
}
