package shinhan.fibri.ieum.ai.question.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class KnowledgeGraphRetrievalServiceTest {

	private static final GeoPoint SEOUL = new GeoPoint(37.5665, 126.9780);
	private static final String CONTENT_HASH = "a".repeat(64);

	private KnowledgeGraphRepository repository;
	private KnowledgeGraphRetrievalService service;

	@BeforeEach
	void setUp() {
		repository = mock(KnowledgeGraphRepository.class);
		service = new KnowledgeGraphRetrievalService(repository);
	}

	@Test
	void rejectsNullCandidateListAndNullElementsBeforeRepositoryAccess() {
		assertThatThrownBy(() -> service.retrieve(null))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("entityCandidates");
		assertThatThrownBy(() -> service.retrieve(Arrays.asList("서울", null)))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("must not contain null");

		verifyNoInteractions(repository);
	}

	@Test
	void validatesEveryCandidateLengthBeforeApplyingTheTwentyCandidateCap() {
		List<String> candidates = new ArrayList<>();
		for (int index = 0; index < 20; index++) {
			candidates.add("후보-" + index);
		}
		candidates.add("가".repeat(201));

		assertThatThrownBy(() -> service.retrieve(candidates))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("200");

		verifyNoInteractions(repository);
	}

	@Test
	void validatesRawLengthBeforeTrimming() {
		assertThatThrownBy(() -> service.retrieve(List.of(" " + "가".repeat(200) + " ")))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("200");

		verifyNoInteractions(repository);
	}

	@Test
	void trimsRemovesBlanksAndDeduplicatesInFirstSeenOrder() {
		when(repository.findOneHopCandidates(List.of("서울", "비자"), 20)).thenReturn(List.of());

		service.retrieve(List.of("  서울  ", "", " ", "비자", " 서울", "비자  "));

		verify(repository).findOneHopCandidates(List.of("서울", "비자"), 20);
	}

	@Test
	void stripsUnicodeWhitespaceAndRemovesUnicodeBlankCandidates() {
		when(repository.findOneHopCandidates(List.of("서울"), 20)).thenReturn(List.of());

		service.retrieve(List.of("\u2003", "\u3000서울\u3000"));

		verify(repository).findOneHopCandidates(List.of("서울"), 20);
	}

	@Test
	void capsNormalizedDistinctCandidatesAtTwenty() {
		List<String> input = new ArrayList<>();
		input.add("후보-0");
		input.add("후보-0");
		for (int index = 1; index < 23; index++) {
			input.add("후보-" + index);
		}
		List<String> expected = java.util.stream.IntStream.range(0, 20)
			.mapToObj(index -> "후보-" + index)
			.toList();
		when(repository.findOneHopCandidates(expected, 20)).thenReturn(List.of());

		service.retrieve(input);

		verify(repository).findOneHopCandidates(expected, 20);
	}

	@Test
	void acceptsAExactlyTwoHundredCharacterCandidate() {
		String candidate = "가".repeat(200);
		when(repository.findOneHopCandidates(List.of(candidate), 20)).thenReturn(List.of());

		service.retrieve(List.of(candidate));

		verify(repository).findOneHopCandidates(List.of(candidate), 20);
	}

	@Test
	void returnsImmutableEmptyListWithoutCallingRepositoryWhenNothingRemains() {
		List<KnowledgeGraphCandidate> result = service.retrieve(List.of("", "  "));

		assertThat(result).isEmpty();
		assertThatThrownBy(() -> result.add(candidate()))
			.isInstanceOf(UnsupportedOperationException.class);
		verifyNoInteractions(repository);
	}

	@Test
	void returnsAnImmutableSnapshotOfRepositoryResults() {
		List<KnowledgeGraphCandidate> repositoryResult = new ArrayList<>(List.of(candidate()));
		when(repository.findOneHopCandidates(List.of("서울"), 20)).thenReturn(repositoryResult);

		List<KnowledgeGraphCandidate> result = service.retrieve(List.of("서울"));
		repositoryResult.clear();

		assertThat(result).hasSize(1);
		assertThatThrownBy(() -> result.clear())
			.isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void delegatesCoordinatesToTheOptionalGeoOverload() {
		when(repository.findOneHopCandidates(List.of("서울"), SEOUL, 20)).thenReturn(List.of());

		service.retrieve(List.of("서울"), SEOUL);

		verify(repository).findOneHopCandidates(List.of("서울"), SEOUL, 20);
		verify(repository, never()).findOneHopCandidates(List.of("서울"), 20);
	}

	@Test
	void candidateNormalizesCanonicalProvenanceAndNullableGeoValues() {
		KnowledgeGraphCandidate candidate = new KnowledgeGraphCandidate(
			" 서울 ",
			" subject ",
			1L,
			2L,
			3L,
			" 서울 ",
			" requires ",
			" 비자 ",
			new BigDecimal("0.9000"),
			" curated ",
			" 출입국 안내 ",
			" 근거 본문 ",
			" GOVERNMENT ",
			" " + CONTENT_HASH + " ",
			" https://www.gov.kr/service?id=1 ",
			" immigration ",
			null,
			null,
			null
		);

		assertThat(candidate.matchedEntity()).isEqualTo("서울");
		assertThat(candidate.matchedSide()).isEqualTo("subject");
		assertThat(candidate.subject()).isEqualTo("서울");
		assertThat(candidate.predicate()).isEqualTo("requires");
		assertThat(candidate.object()).isEqualTo("비자");
		assertThat(candidate.sourceType()).isEqualTo("curated");
		assertThat(candidate.title()).isEqualTo("출입국 안내");
		assertThat(candidate.excerpt()).isEqualTo("근거 본문");
		assertThat(candidate.sourceGrade()).isEqualTo("government");
		assertThat(candidate.contentHash()).isEqualTo(CONTENT_HASH);
		assertThat(candidate.canonicalUrl()).isEqualTo("https://www.gov.kr/service?id=1");
		assertThat(candidate.riskDomain()).isEqualTo("immigration");
		assertThat(candidate.sourceGeoScope()).isNull();
		assertThat(candidate.sourceRegionContext()).isEqualTo(RegionContext.empty());
		assertThat(candidate.distanceKm()).isNull();
	}

	@Test
	void candidateRejectsInvalidRelationAndProvenanceValues() {
		assertThatThrownBy(() -> candidate(0L, "subject", "requires", new BigDecimal("0.5"), null))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("relationId");
		assertThatThrownBy(() -> candidate(1L, "both", "requires", new BigDecimal("0.5"), null))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("matchedSide");
		assertThatThrownBy(() -> candidate(1L, "subject", "relates_to", new BigDecimal("0.5"), null))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("predicate");
		assertThatThrownBy(() -> candidate(1L, "subject", "requires", new BigDecimal("1.0001"), null))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("relationConfidence");
		assertThatThrownBy(() -> candidate(1L, "subject", "requires", new BigDecimal("0.5"), -0.1d))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("distanceKm");
	}

	@Test
	void candidatePredicateVocabularyMatchesTheV1Contract() {
		assertThat(List.of(
			"requires",
			"applies_to",
			"located_in",
			"exception_of",
			"prevents",
			"supports",
			"has_deadline",
			"depends_on",
			"reported_to",
			"used_for"
		)).allSatisfy(predicate -> assertThat(candidate(
			1L,
			"subject",
			predicate,
			new BigDecimal("0.5"),
			null
		).predicate()).isEqualTo(predicate));
	}

	private KnowledgeGraphCandidate candidate() {
		return candidate(1L, "subject", "requires", new BigDecimal("0.9000"), null);
	}

	private KnowledgeGraphCandidate candidate(
		long relationId,
		String matchedSide,
		String predicate,
		BigDecimal confidence,
		Double distanceKm
	) {
		return new KnowledgeGraphCandidate(
			"서울",
			matchedSide,
			relationId,
			2L,
			3L,
			"서울",
			predicate,
			"비자",
			confidence,
			"curated",
			"출입국 안내",
			"근거 본문",
			"government",
			CONTENT_HASH,
			"https://www.gov.kr/service?id=1",
			"immigration",
			GeoScope.general,
			RegionContext.empty(),
			distanceKm
		);
	}
}
