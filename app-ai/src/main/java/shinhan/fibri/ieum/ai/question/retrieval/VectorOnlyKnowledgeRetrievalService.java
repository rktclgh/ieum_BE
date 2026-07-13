package shinhan.fibri.ieum.ai.question.retrieval;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class VectorOnlyKnowledgeRetrievalService {

	private static final Comparator<VectorKnowledgeCandidate> VECTOR_ORDER = Comparator
		.comparingDouble(VectorKnowledgeCandidate::cosineSimilarity)
		.reversed()
		.thenComparingLong(VectorKnowledgeCandidate::sourceId)
		.thenComparingLong(VectorKnowledgeCandidate::chunkId);

	private final VectorKnowledgeRepository repository;
	private final VectorKnowledgeRetrievalConfig config;
	private final VectorKnowledgeScorer scorer;

	@Autowired
	public VectorOnlyKnowledgeRetrievalService(VectorKnowledgeRepository repository) {
		this(repository, VectorKnowledgeRetrievalConfig.defaults());
	}

	VectorOnlyKnowledgeRetrievalService(
		VectorKnowledgeRepository repository,
		VectorKnowledgeRetrievalConfig config
	) {
		this.repository = Objects.requireNonNull(repository, "repository must not be null");
		this.config = Objects.requireNonNull(config, "config must not be null");
		this.scorer = new VectorKnowledgeScorer(config);
	}

	public VectorKnowledgeRetrievalResult retrieve(VectorKnowledgeRetrievalRequest request) {
		Objects.requireNonNull(request, "request must not be null");
		List<VectorKnowledgeCandidate> globalCandidates = repository.findGlobalCandidates(
			request.embedding(),
			config.globalOverfetch()
		);
		List<VectorKnowledgeCandidate> locationCandidates = !shouldQueryLocationLane(request)
			? List.of()
			: repository.findLocationAwareCandidates(
				request.embedding(),
				request.regionContext(),
				request.coordinates(),
				config.geoOverfetch()
			);

		List<VectorKnowledgeCandidate> ranked = mergeCandidates(globalCandidates, locationCandidates);
		List<VectorKnowledgeEvidence> scored = new ArrayList<>(ranked.size());
		for (int index = 0; index < ranked.size(); index++) {
			scored.add(scorer.score(ranked.get(index), index + 1, request));
		}

		Set<Long> eligibleChunkIds = repository.findEligibleChunkIds(chunkIds(scored));
		List<VectorKnowledgeEvidence> candidates = scored.stream()
			.filter(candidate -> eligibleChunkIds.contains(candidate.chunkId()))
			.sorted(finalOrder())
			.toList();
		List<VectorKnowledgeEvidence> evidence = candidates.stream()
			.limit(config.evidenceLimit())
			.toList();

		return new VectorKnowledgeRetrievalResult(
			config.retrievalConfigVersion(),
			candidates,
			evidence
		);
	}

	public List<VectorKnowledgeEvidence> revalidateEvidence(List<VectorKnowledgeEvidence> evidence) {
		Objects.requireNonNull(evidence, "evidence must not be null");
		Set<Long> eligibleChunkIds = repository.findEligibleChunkIds(chunkIds(evidence));
		return evidence.stream()
			.filter(item -> eligibleChunkIds.contains(item.chunkId()))
			.sorted(finalOrder())
			.limit(config.evidenceLimit())
			.toList();
	}

	static Comparator<VectorKnowledgeEvidence> finalOrder() {
		return Comparator.comparing(VectorKnowledgeEvidence::finalScore)
			.reversed()
			.thenComparingLong(VectorKnowledgeEvidence::sourceId)
			.thenComparingLong(VectorKnowledgeEvidence::chunkId);
	}

	private List<VectorKnowledgeCandidate> mergeCandidates(
		List<VectorKnowledgeCandidate> globalCandidates,
		List<VectorKnowledgeCandidate> locationCandidates
	) {
		Map<Long, VectorKnowledgeCandidate> byChunkId = new LinkedHashMap<>();
		for (VectorKnowledgeCandidate candidate : globalCandidates.stream()
			.sorted(VECTOR_ORDER)
			.limit(config.laneCandidateLimit())
			.toList()) {
			merge(byChunkId, candidate);
		}
		for (VectorKnowledgeCandidate candidate : locationCandidates.stream()
			.limit(config.laneCandidateLimit())
			.toList()) {
			merge(byChunkId, candidate);
		}
		return byChunkId.values().stream()
			.filter(candidate -> Double.isFinite(candidate.cosineSimilarity()))
			.sorted(VECTOR_ORDER)
			.toList();
	}

	private boolean shouldQueryLocationLane(VectorKnowledgeRetrievalRequest request) {
		return request.coordinates() != null || request.regionContext().hasSido();
	}

	private void merge(Map<Long, VectorKnowledgeCandidate> byChunkId, VectorKnowledgeCandidate candidate) {
		Objects.requireNonNull(candidate, "candidate must not be null");
		byChunkId.merge(
			candidate.chunkId(),
			candidate,
			(existing, replacement) -> existing.distanceKm() == null && replacement.distanceKm() != null
				? replacement
				: existing
		);
	}

	private Set<Long> chunkIds(Collection<VectorKnowledgeEvidence> evidence) {
		LinkedHashSet<Long> chunkIds = new LinkedHashSet<>();
		for (VectorKnowledgeEvidence item : evidence) {
			if (item != null) {
				chunkIds.add(item.chunkId());
			}
		}
		return chunkIds;
	}
}
