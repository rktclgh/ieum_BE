package shinhan.fibri.ieum.ai.question.retrieval;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;

@Service
public class KnowledgeGraphRetrievalService {

	static final int MAX_ENTITY_CANDIDATES = 20;
	static final int MAX_ENTITY_LENGTH = 200;

	private final KnowledgeGraphRepository repository;

	public KnowledgeGraphRetrievalService(KnowledgeGraphRepository repository) {
		this.repository = Objects.requireNonNull(repository, "repository must not be null");
	}

	public List<KnowledgeGraphCandidate> retrieve(List<String> entityCandidates) {
		List<String> normalized = normalize(entityCandidates);
		if (normalized.isEmpty()) {
			return List.of();
		}
		return List.copyOf(repository.findOneHopCandidates(normalized, MAX_ENTITY_CANDIDATES));
	}

	public List<KnowledgeGraphCandidate> retrieve(
		List<String> entityCandidates,
		GeoPoint coordinates
	) {
		if (coordinates == null) {
			return retrieve(entityCandidates);
		}
		List<String> normalized = normalize(entityCandidates);
		if (normalized.isEmpty()) {
			return List.of();
		}
		return List.copyOf(repository.findOneHopCandidates(
			normalized,
			coordinates,
			MAX_ENTITY_CANDIDATES
		));
	}

	private List<String> normalize(List<String> entityCandidates) {
		if (entityCandidates == null) {
			throw new IllegalArgumentException("entityCandidates must not be null");
		}
		for (String candidate : entityCandidates) {
			if (candidate == null) {
				throw new IllegalArgumentException("entityCandidates must not contain null");
			}
			if (candidate.length() > MAX_ENTITY_LENGTH) {
				throw new IllegalArgumentException("entity candidate must not exceed 200 characters");
			}
		}

		LinkedHashSet<String> normalized = new LinkedHashSet<>();
		for (String candidate : entityCandidates) {
			String value = candidate.strip();
			if (!value.isBlank()) {
				normalized.add(value);
			}
		}
		return normalized.stream()
			.limit(MAX_ENTITY_CANDIDATES)
			.toList();
	}
}
