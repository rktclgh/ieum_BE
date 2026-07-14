package shinhan.fibri.ieum.ai.question.generation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import shinhan.fibri.ieum.ai.question.citation.AnswerCitation;

public final class LocalAnswerOutputParser {

	private static final Set<String> ROOT_FIELDS = Set.of("answer", "citations");
	private static final Set<String> CITATION_FIELDS = Set.of("evidenceIndex", "startIndex", "endIndex");

	private final ObjectReader strictReader;

	public LocalAnswerOutputParser(ObjectMapper objectMapper) {
		Objects.requireNonNull(objectMapper, "objectMapper must not be null");
		this.strictReader = objectMapper.readerFor(JsonNode.class).with(
			DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
			DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY
		);
	}

	public ParsedLocalAnswer parse(String rawOutput, LocalAnswerPrompt prompt) {
		Objects.requireNonNull(prompt, "prompt must not be null");
		if (rawOutput == null || rawOutput.isBlank()) {
			throw invalid(LocalAnswerProviderFailureCode.empty_response);
		}
		try {
			JsonNode root = strictReader.readTree(rawOutput);
			requireExactObject(root, ROOT_FIELDS);
			String answer = requiredAnswer(root.get("answer"));
			List<AnswerCitation> citations = citations(root.get("citations"), answer, prompt.evidence().size());
			return new ParsedLocalAnswer(answer, citations);
		}
		catch (InvalidLocalAnswerOutputException exception) {
			throw exception;
		}
		catch (JsonProcessingException | RuntimeException exception) {
			throw invalid(LocalAnswerProviderFailureCode.invalid_output);
		}
	}

	private String requiredAnswer(JsonNode node) {
		if (node == null || !node.isTextual() || node.textValue().isBlank()) {
			throw invalid(LocalAnswerProviderFailureCode.invalid_output);
		}
		return node.textValue();
	}

	private List<AnswerCitation> citations(JsonNode node, String answer, int evidenceCount) {
		if (node == null || !node.isArray() || node.isEmpty() || node.size() > 8) {
			throw invalid(LocalAnswerProviderFailureCode.invalid_output);
		}
		List<AnswerCitation> citations = new ArrayList<>(node.size());
		Set<AnswerRange> ranges = new HashSet<>();
		for (JsonNode citationNode : node) {
			requireExactObject(citationNode, CITATION_FIELDS);
			int evidenceIndex = requiredInteger(citationNode.get("evidenceIndex"));
			int startIndex = requiredInteger(citationNode.get("startIndex"));
			int endIndex = requiredInteger(citationNode.get("endIndex"));
			if (evidenceIndex < 0 || evidenceIndex >= evidenceCount
				|| startIndex < 0 || endIndex <= startIndex || endIndex > answer.length()) {
				throw invalid(LocalAnswerProviderFailureCode.invalid_output);
			}
			if (!isUnicodeBoundary(answer, startIndex) || !isUnicodeBoundary(answer, endIndex)) {
				throw invalid(LocalAnswerProviderFailureCode.invalid_output);
			}
			if (!ranges.add(new AnswerRange(startIndex, endIndex))) {
				throw invalid(LocalAnswerProviderFailureCode.invalid_output);
			}
			citations.add(new AnswerCitation(evidenceIndex, startIndex, endIndex));
		}
		return List.copyOf(citations);
	}

	private int requiredInteger(JsonNode node) {
		if (node == null || !node.isIntegralNumber() || !node.canConvertToInt()) {
			throw invalid(LocalAnswerProviderFailureCode.invalid_output);
		}
		return node.intValue();
	}

	private void requireExactObject(JsonNode node, Set<String> expectedFields) {
		if (node == null || !node.isObject()) {
			throw invalid(LocalAnswerProviderFailureCode.invalid_output);
		}
		Set<String> actualFields = new HashSet<>();
		node.fieldNames().forEachRemaining(actualFields::add);
		if (!actualFields.equals(expectedFields)) {
			throw invalid(LocalAnswerProviderFailureCode.invalid_output);
		}
	}

	private InvalidLocalAnswerOutputException invalid(LocalAnswerProviderFailureCode code) {
		return new InvalidLocalAnswerOutputException(code);
	}

	private boolean isUnicodeBoundary(String value, int index) {
		return index <= 0
			|| index >= value.length()
			|| !(Character.isHighSurrogate(value.charAt(index - 1))
				&& Character.isLowSurrogate(value.charAt(index)));
	}

	private record AnswerRange(int start, int end) {
	}
}
