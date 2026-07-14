package shinhan.fibri.ieum.ai.question.retrieval;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class HybridKnowledgeRetrievalService {

	private final VectorOnlyKnowledgeRetrievalService vectorService;
	private final KnowledgeGraphRetrievalService graphService;
	private final VectorKnowledgeRepository vectorRepository;
	private final KnowledgeGraphRepository graphRepository;
	private final WeightedRrfFusion fusion;
	private final int evidenceLimit;
	private final Clock clock;

	@Autowired
	public HybridKnowledgeRetrievalService(
		VectorOnlyKnowledgeRetrievalService vectorService,
		KnowledgeGraphRetrievalService graphService,
		VectorKnowledgeRepository vectorRepository,
		KnowledgeGraphRepository graphRepository
	) {
		this(
			vectorService,
			graphService,
			vectorRepository,
			graphRepository,
			VectorKnowledgeRetrievalConfig.defaults(),
			Clock.systemUTC()
		);
	}

	HybridKnowledgeRetrievalService(
		VectorOnlyKnowledgeRetrievalService vectorService,
		KnowledgeGraphRetrievalService graphService,
		VectorKnowledgeRepository vectorRepository,
		KnowledgeGraphRepository graphRepository,
		VectorKnowledgeRetrievalConfig config,
		Clock clock
	) {
		this.vectorService = Objects.requireNonNull(vectorService, "vectorService must not be null");
		this.graphService = Objects.requireNonNull(graphService, "graphService must not be null");
		this.vectorRepository = Objects.requireNonNull(vectorRepository, "vectorRepository must not be null");
		this.graphRepository = Objects.requireNonNull(graphRepository, "graphRepository must not be null");
		VectorKnowledgeRetrievalConfig requiredConfig = Objects.requireNonNull(
			config,
			"config must not be null"
		);
		this.fusion = new WeightedRrfFusion(requiredConfig);
		this.evidenceLimit = requiredConfig.evidenceLimit();
		this.clock = Objects.requireNonNull(clock, "clock must not be null");
	}

	public HybridKnowledgeRetrievalResult retrieve(
		VectorKnowledgeRetrievalRequest request,
		List<String> entityCandidates
	) {
		Objects.requireNonNull(request, "request must not be null");
		Objects.requireNonNull(entityCandidates, "entityCandidates must not be null");
		VectorKnowledgeRetrievalResult vectorResult = vectorService.retrieve(request);
		List<KnowledgeGraphCandidate> graphCandidates = graphService.retrieve(
			entityCandidates,
			request.coordinates()
		);
		Instant retrievedAt = Objects.requireNonNull(clock.instant(), "clock instant must not be null");
		List<KnowledgeEvidence> fused = fusion.fuse(
			vectorResult.candidates(),
			graphCandidates,
			request,
			retrievedAt
		);
		List<KnowledgeEvidence> evidence = revalidateEvidence(fused);

		return new HybridKnowledgeRetrievalResult(
			fusion.retrievalConfigVersion(),
			fused,
			evidence
		);
	}

	public List<KnowledgeEvidence> revalidateEvidence(
		List<? extends KnowledgeEvidence> candidates
	) {
		Objects.requireNonNull(candidates, "candidates must not be null");
		List<KnowledgeEvidence> snapshot = List.copyOf(candidates);
		Set<Long> eligibleChunkIds = vectorRepository.findEligibleChunkIds(chunkIds(snapshot));
		Set<Long> relationIds = relationIds(snapshot);
		Set<Long> eligibleRelationIds = relationIds.isEmpty()
			? Set.of()
			: graphRepository.findEligibleRelationIds(relationIds);
		return snapshot.stream()
			.filter(item -> eligibleChunkIds.contains(item.chunkId()))
			.filter(item -> item.relationId() == null || eligibleRelationIds.contains(item.relationId()))
			.sorted(WeightedRrfFusion.finalOrder())
			.limit(evidenceLimit)
			.toList();
	}

	private Set<Long> chunkIds(Collection<? extends KnowledgeEvidence> evidence) {
		LinkedHashSet<Long> chunkIds = new LinkedHashSet<>();
		for (KnowledgeEvidence item : evidence) {
			chunkIds.add(item.chunkId());
		}
		return chunkIds;
	}

	private Set<Long> relationIds(Collection<? extends KnowledgeEvidence> evidence) {
		LinkedHashSet<Long> relationIds = new LinkedHashSet<>();
		for (KnowledgeEvidence item : evidence) {
			if (item.relationId() != null) {
				relationIds.add(item.relationId());
			}
		}
		return relationIds;
	}
}
