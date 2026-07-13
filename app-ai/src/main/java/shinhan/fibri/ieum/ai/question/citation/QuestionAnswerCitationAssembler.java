package shinhan.fibri.ieum.ai.question.citation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import shinhan.fibri.ieum.ai.question.retrieval.VectorKnowledgeEvidence;

public class QuestionAnswerCitationAssembler {

	private static final int MAX_CITATIONS = 8;
	private static final Comparator<AnswerCitation> ANSWER_ORDER = Comparator
		.comparingInt(AnswerCitation::startIndex)
		.thenComparingInt(AnswerCitation::endIndex)
		.thenComparingInt(AnswerCitation::evidenceIndex);

	private final ObjectMapper objectMapper;

	public QuestionAnswerCitationAssembler(ObjectMapper objectMapper) {
		this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
	}

	public List<JsonNode> assemble(
		String answer,
		List<VectorKnowledgeEvidence> retrievedEvidence,
		List<AnswerCitation> citations
	) {
		Objects.requireNonNull(answer, "answer must not be null");
		if (answer.isBlank()) {
			throw new IllegalArgumentException("answer must not be blank");
		}
		Objects.requireNonNull(retrievedEvidence, "retrievedEvidence must not be null");
		Objects.requireNonNull(citations, "citations must not be null");
		if (citations.isEmpty() || citations.size() > MAX_CITATIONS) {
			throw new IllegalArgumentException("citations must contain 1 to 8 items");
		}
		for (VectorKnowledgeEvidence evidence : retrievedEvidence) {
			Objects.requireNonNull(evidence, "retrievedEvidence must not contain null");
		}

		List<AnswerCitation> ordered = validateAndOrder(answer, retrievedEvidence.size(), citations);
		List<JsonNode> assembled = new ArrayList<>(ordered.size());
		for (AnswerCitation citation : ordered) {
			assembled.add(toEvidenceJson(retrievedEvidence.get(citation.evidenceIndex()), citation));
		}
		return List.copyOf(assembled);
	}

	private List<AnswerCitation> validateAndOrder(
		String answer,
		int evidenceCount,
		List<AnswerCitation> citations
	) {
		Set<CitationRange> citedRanges = new HashSet<>();
		List<AnswerCitation> ordered = new ArrayList<>(citations.size());
		for (AnswerCitation citation : citations) {
			Objects.requireNonNull(citation, "citations must not contain null");
			if (citation.evidenceIndex() >= evidenceCount) {
				throw new IllegalArgumentException("citation evidenceIndex must refer to retrieved evidence");
			}
			if (citation.endIndex() > answer.length()) {
				throw new IllegalArgumentException("citation indices must be inside the Java String answer length");
			}
			if (splitsSurrogatePair(answer, citation.startIndex())
				|| splitsSurrogatePair(answer, citation.endIndex())) {
				throw new IllegalArgumentException("citation indices must not split a UTF-16 surrogate pair");
			}
			if (!citedRanges.add(new CitationRange(citation.startIndex(), citation.endIndex()))) {
				throw new IllegalArgumentException("each citation range must be unique");
			}
			ordered.add(citation);
		}
		ordered.sort(ANSWER_ORDER);
		return ordered;
	}

	private boolean splitsSurrogatePair(String answer, int index) {
		return index > 0
			&& index < answer.length()
			&& Character.isHighSurrogate(answer.charAt(index - 1))
			&& Character.isLowSurrogate(answer.charAt(index));
	}

	private ObjectNode toEvidenceJson(VectorKnowledgeEvidence evidence, AnswerCitation citation) {
		ObjectNode node = objectMapper.createObjectNode();
		node.put("type", "knowledge_chunk");
		node.put("sourceId", evidence.sourceId());
		node.put("chunkId", evidence.chunkId());
		node.put("sourceType", evidence.sourceType());
		node.put("title", evidence.title());
		node.put("excerpt", evidence.excerpt());
		putOptional(node, "url", evidence.canonicalUrl());
		putOptional(node, "domain", evidence.domain());
		node.put("contentHash", evidence.contentHash());
		node.put("score", evidence.finalScore());
		node.put("startIndex", citation.startIndex());
		node.put("endIndex", citation.endIndex());
		node.put("retrievedAt", evidence.retrievedAt().toString());
		return node;
	}

	private void putOptional(ObjectNode node, String field, String value) {
		if (value != null) {
			node.put(field, value);
		}
	}

	private record CitationRange(int startIndex, int endIndex) {
	}
}
