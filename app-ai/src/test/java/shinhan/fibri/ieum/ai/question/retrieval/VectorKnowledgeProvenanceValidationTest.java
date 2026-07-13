package shinhan.fibri.ieum.ai.question.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class VectorKnowledgeProvenanceValidationTest {

	private static final String CONTENT_HASH = "a".repeat(64);
	private static final Instant RETRIEVED_AT = Instant.parse("2026-07-13T03:04:05Z");

	@Test
	void candidateNormalizesCanonicalProvenance() {
		VectorKnowledgeCandidate candidate = candidate(
			" " + CONTENT_HASH + " ",
			" https://www.gov.kr/service?id=1 ",
			" GOVERNMENT "
		);

		assertThat(candidate.contentHash()).isEqualTo(CONTENT_HASH);
		assertThat(candidate.canonicalUrl()).isEqualTo("https://www.gov.kr/service?id=1");
		assertThat(candidate.sourceGrade()).isEqualTo("government");
		assertThat(candidate.title()).isEqualTo("source title");
		assertThat(candidate.excerpt()).isEqualTo("source excerpt");
	}

	@Test
	void candidateRejectsInvalidHashAndUnsafeCanonicalUrl() {
		assertThatThrownBy(() -> candidate("A".repeat(64), "https://www.gov.kr", "government"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("contentHash");
		assertThatThrownBy(() -> candidate("a".repeat(63), "https://www.gov.kr", "government"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("contentHash");
		assertThatThrownBy(() -> candidate(CONTENT_HASH, "ftp://www.gov.kr/file", "government"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("canonicalUrl");
		assertThatThrownBy(() -> candidate(CONTENT_HASH, "https://user:secret@www.gov.kr/file", "government"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("canonicalUrl");
	}

	@Test
	void evidenceRejectsInvalidIdentityTextScoreAndRetrievalTime() {
		assertThatThrownBy(() -> evidence(0L, 1L, "curated", "title", "excerpt", score("0.5"), RETRIEVED_AT))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("sourceId");
		assertThatThrownBy(() -> evidence(1L, 0L, "curated", "title", "excerpt", score("0.5"), RETRIEVED_AT))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("chunkId");
		assertThatThrownBy(() -> evidence(1L, 1L, " ", "title", "excerpt", score("0.5"), RETRIEVED_AT))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("sourceType");
		assertThatThrownBy(() -> evidence(1L, 1L, "curated", " ", "excerpt", score("0.5"), RETRIEVED_AT))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("title");
		assertThatThrownBy(() -> evidence(1L, 1L, "curated", "title", " ", score("0.5"), RETRIEVED_AT))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("excerpt");
		assertThatThrownBy(() -> evidence(1L, 1L, "curated", "title", "excerpt", score("1.000001"), RETRIEVED_AT))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("finalScore");
		assertThatThrownBy(() -> evidence(1L, 1L, "curated", "title", "excerpt", score("0.5"), null))
			.isInstanceOf(NullPointerException.class)
			.hasMessageContaining("retrievedAt");
	}

	private VectorKnowledgeCandidate candidate(String contentHash, String canonicalUrl, String sourceGrade) {
		return new VectorKnowledgeCandidate(
			1L,
			2L,
			" curated ",
			" source title ",
			" source excerpt ",
			sourceGrade,
			contentHash,
			canonicalUrl,
			" immigration ",
			" public-service ",
			GeoScope.general,
			RegionContext.empty(),
			0.9d,
			null
		);
	}

	private VectorKnowledgeEvidence evidence(
		long sourceId,
		long chunkId,
		String sourceType,
		String title,
		String excerpt,
		BigDecimal finalScore,
		Instant retrievedAt
	) {
		return new VectorKnowledgeEvidence(
			sourceId,
			chunkId,
			sourceType,
			title,
			excerpt,
			"community",
			CONTENT_HASH,
			"https://example.com/source",
			"transportation",
			"community",
			GeoScope.general,
			score("0.9"),
			score("0.7"),
			score("0.5"),
			finalScore,
			null,
			retrievedAt
		);
	}

	private BigDecimal score(String value) {
		return new BigDecimal(value);
	}
}
