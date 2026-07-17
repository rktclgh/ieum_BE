package shinhan.fibri.ieum.ai.question.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class HybridKnowledgeRetrievalServiceTest {

	private static final Instant RETRIEVED_AT = Instant.parse("2026-07-14T02:03:04Z");

	private final VectorOnlyKnowledgeRetrievalService vectorService = mock(
		VectorOnlyKnowledgeRetrievalService.class
	);
	private final KnowledgeGraphRetrievalService graphService = mock(KnowledgeGraphRetrievalService.class);
	private final VectorKnowledgeRepository vectorRepository = mock(VectorKnowledgeRepository.class);
	private final KnowledgeGraphRepository graphRepository = mock(KnowledgeGraphRepository.class);
	private final VectorKnowledgeRetrievalConfig config = VectorKnowledgeRetrievalConfig.defaults();
	private final Clock clock = Clock.fixed(RETRIEVED_AT, ZoneOffset.UTC);
	private HybridKnowledgeRetrievalService service;

	@BeforeEach
	void setUp() {
		service = new HybridKnowledgeRetrievalService(
			vectorService,
			graphService,
			vectorRepository,
			graphRepository,
			config,
			clock
		);
	}

	@Test
	void readsFullVectorCandidatesAndAnalyzerEntitiesWithRequestCoordinates() {
		VectorKnowledgeRetrievalRequest request = request();
		List<String> entityCandidates = List.of("서울시", "아이돌봄서비스");
		VectorKnowledgeEvidence vectorOnly = vectorEvidence(1L, 11L, "0.91");
		VectorKnowledgeEvidence outsideVectorEvidenceCap = vectorEvidence(2L, 22L, "0.81");
		KnowledgeGraphCandidate relation = relation(202L, 2L, 22L);
		when(vectorService.retrieve(same(request))).thenReturn(new VectorKnowledgeRetrievalResult(
			"retrieval-v1",
			List.of(vectorOnly, outsideVectorEvidenceCap),
			List.of(vectorOnly)
		));
		when(graphService.retrieve(eq(entityCandidates), same(request.coordinates())))
			.thenReturn(List.of(relation));
		when(vectorRepository.findEligibleChunkIds(any())).thenReturn(Set.of(11L, 22L));
		when(graphRepository.findEligibleRelationIds(any())).thenReturn(Set.of(202L));

		HybridKnowledgeRetrievalResult result = service.retrieve(request, entityCandidates);

		verify(vectorService).retrieve(same(request));
		verify(graphService).retrieve(eq(entityCandidates), same(request.coordinates()));
		assertThat(result.retrievalConfigVersion()).isEqualTo("retrieval-v3-hybrid-kg2");
		assertThat(result.candidates()).hasSize(2);
		assertThat(result.candidates().stream()
			.filter(item -> item.chunkId() == 11L)
			.findFirst()
			.orElseThrow()).isSameAs(vectorOnly);
		assertThat(result.candidates().stream()
			.filter(item -> item.chunkId() == 22L)
			.findFirst()
			.orElseThrow()).isInstanceOf(HybridKnowledgeEvidence.class);
	}

	@Test
	void revalidatesTheFullUnionBeforeLimitingAndBackfillsRemovedTopEvidence() {
		VectorKnowledgeRetrievalRequest request = request();
		List<VectorKnowledgeEvidence> candidates = new ArrayList<>();
		for (int index = 1; index <= 10; index++) {
			candidates.add(vectorEvidence(index, index, BigDecimal.valueOf(100 - index, 2).toPlainString()));
		}
		when(vectorService.retrieve(same(request))).thenReturn(new VectorKnowledgeRetrievalResult(
			"retrieval-v1",
			candidates,
			candidates.subList(0, 8)
		));
		when(graphService.retrieve(any(), same(request.coordinates()))).thenReturn(List.of());
		when(vectorRepository.findEligibleChunkIds(any())).thenReturn(new LinkedHashSet<>(
			List.of(2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L)
		));

		HybridKnowledgeRetrievalResult result = service.retrieve(request, List.of("버스"));

		@SuppressWarnings("unchecked")
		ArgumentCaptor<Collection<Long>> chunkIds = ArgumentCaptor.forClass(Collection.class);
		verify(vectorRepository).findEligibleChunkIds(chunkIds.capture());
		assertThat(chunkIds.getValue()).containsExactlyInAnyOrder(
			1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L
		);
		assertThat(result.candidates()).extracting(KnowledgeEvidence::chunkId)
			.containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L);
		assertThat(result.evidence()).extracting(KnowledgeEvidence::chunkId)
			.containsExactly(2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L);
	}

	@Test
	void requiresEligibleChunkAndEligibleRelationForEveryKgBackedItem() {
		VectorKnowledgeRetrievalRequest request = request();
		VectorKnowledgeEvidence vectorOnly = vectorEvidence(1L, 1L, "0.90");
		VectorKnowledgeEvidence bothLanes = vectorEvidence(2L, 2L, "0.80");
		when(vectorService.retrieve(same(request))).thenReturn(new VectorKnowledgeRetrievalResult(
			"retrieval-v1",
			List.of(vectorOnly, bothLanes),
			List.of(vectorOnly, bothLanes)
		));
		when(graphService.retrieve(any(), same(request.coordinates()))).thenReturn(List.of(
			relation(202L, 2L, 2L),
			relation(303L, 3L, 3L),
			relation(404L, 4L, 4L)
		));
		when(vectorRepository.findEligibleChunkIds(any())).thenReturn(Set.of(1L, 2L, 3L));
		when(graphRepository.findEligibleRelationIds(any())).thenReturn(Set.of(303L, 404L));

		HybridKnowledgeRetrievalResult result = service.retrieve(request, List.of("돌봄"));

		@SuppressWarnings("unchecked")
		ArgumentCaptor<Collection<Long>> chunkIds = ArgumentCaptor.forClass(Collection.class);
		@SuppressWarnings("unchecked")
		ArgumentCaptor<Collection<Long>> relationIds = ArgumentCaptor.forClass(Collection.class);
		verify(vectorRepository).findEligibleChunkIds(chunkIds.capture());
		verify(graphRepository).findEligibleRelationIds(relationIds.capture());
		assertThat(chunkIds.getValue()).containsExactlyInAnyOrder(1L, 2L, 3L, 4L);
		assertThat(relationIds.getValue()).containsExactlyInAnyOrder(202L, 303L, 404L);
		assertThat(result.candidates()).extracting(KnowledgeEvidence::chunkId)
			.containsExactlyInAnyOrder(1L, 2L, 3L, 4L);
		assertThat(result.evidence()).extracting(KnowledgeEvidence::chunkId)
			.containsExactlyInAnyOrder(1L, 3L);
		assertThat(result.evidence().stream()
			.filter(item -> item.chunkId() == 1L)
			.findFirst()
			.orElseThrow()).isSameAs(vectorOnly);
		assertThat(result.evidence().stream()
			.filter(item -> item.chunkId() == 3L)
			.findFirst()
			.orElseThrow()).isInstanceOf(HybridKnowledgeEvidence.class);
	}

	@Test
	void kgEmptyPreservesVectorOnlyIdentityAndOrderWithOnlyTheV2VersionChanged() {
		VectorKnowledgeRetrievalRequest request = request();
		VectorKnowledgeEvidence first = vectorEvidence(1L, 1L, "0.90");
		VectorKnowledgeEvidence second = vectorEvidence(2L, 2L, "0.80");
		when(vectorService.retrieve(same(request))).thenReturn(new VectorKnowledgeRetrievalResult(
			"retrieval-v1",
			List.of(first, second),
			List.of(first, second)
		));
		when(graphService.retrieve(any(), same(request.coordinates()))).thenReturn(List.of());
		when(vectorRepository.findEligibleChunkIds(any())).thenReturn(Set.of(1L, 2L));

		HybridKnowledgeRetrievalResult result = service.retrieve(request, List.of("버스"));

		assertThat(result.retrievalConfigVersion()).isEqualTo("retrieval-v3-hybrid-kg2");
		assertThat(result.candidates()).containsExactly(first, second);
		assertThat(result.evidence()).containsExactly(first, second);
		assertThat(result.evidence().get(0)).isSameAs(first);
		assertThat(result.evidence().get(1)).isSameAs(second);
	}

	private VectorKnowledgeRetrievalRequest request() {
		return new VectorKnowledgeRetrievalRequest(
			unitVector(),
			GeoScope.local,
			new GeoPoint(37.5665d, 126.9780d),
			new RegionContext("서울특별시", "중구")
		);
	}

	private VectorKnowledgeEvidence vectorEvidence(long sourceId, long chunkId, String score) {
		BigDecimal value = new BigDecimal(score);
		return new VectorKnowledgeEvidence(
			sourceId,
			chunkId,
			"curated",
			"source-" + sourceId,
			"vector-content-" + chunkId,
			"public_agency",
			"a".repeat(64),
			"https://example.com/source/" + sourceId,
			"transportation",
			"community",
			GeoScope.general,
			value,
			value,
			BigDecimal.ZERO,
			value,
			null,
			RETRIEVED_AT
		);
	}

	private KnowledgeGraphCandidate relation(long relationId, long sourceId, long chunkId) {
		return new KnowledgeGraphCandidate(
			"돌봄서비스",
			"subject",
			relationId,
			sourceId,
			chunkId,
			"돌봄서비스",
			"supports",
			"가정",
			new BigDecimal("0.90"),
			"curated",
			"source-" + sourceId,
			"관계 근거 " + chunkId,
			"public_agency",
			"b".repeat(64),
			"https://example.com/source/" + sourceId,
			"transportation",
			GeoScope.local,
			new RegionContext("서울특별시", "중구"),
			1.5d
		);
	}

	private List<Float> unitVector() {
		List<Float> values = new ArrayList<>(Collections.nCopies(768, 0.0f));
		values.set(0, 1.0f);
		return values;
	}
}
