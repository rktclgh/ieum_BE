package shinhan.fibri.ieum.ai.knowledge.relations;

import java.util.List;
import java.util.Objects;

public record CandidateExtractionResult(
	List<ExtractedKnowledgeRelationCandidate> candidates,
	String provider,
	String model
) {

	public CandidateExtractionResult {
		candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates must not be null"));
		provider = normalize(provider, "provider");
		model = normalize(model, "model");
	}

	public static CandidateExtractionResult valid(List<ExtractedKnowledgeRelationCandidate> candidates) {
		return new CandidateExtractionResult(candidates, "test", "deterministic");
	}

	private static String normalize(String value, String field) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(field + " must not be blank");
		}
		return value.trim();
	}
}
