package shinhan.fibri.ieum.ai.knowledge.packageimport;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public record KnowledgeSeedPackage(
	String schemaVersion,
	String packageKey,
	String packageVersion,
	String canonicalDraft,
	String documentType,
	boolean answerEligible,
	String language,
	String embeddingPolicy,
	String manifestHash,
	int expectedSourceCount,
	List<String> predicateVocabulary,
	String reviewView,
	List<Source> sources
) {

	public KnowledgeSeedPackage {
		predicateVocabulary = immutableList(predicateVocabulary);
		sources = immutableList(sources);
	}

	public record Source(
		String sourceKey,
		String displayName,
		String documentType,
		boolean answerEligible,
		String sourceGrade,
		String authorityLevel,
		Map<String, String> jurisdiction,
		String geoScope,
		Map<String, String> regionContext,
		List<String> audience,
		List<String> dependencies,
		String riskDomain,
		String retrievedAt,
		String verifiedAt,
		String effectiveFrom,
		String validUntil,
		int reviewIntervalDays,
		String canonicalUrl,
		List<String> supportingUrls,
		String contentHash,
		List<Chunk> chunks,
		List<Relation> relations
	) {

		public Source {
			jurisdiction = immutableMap(jurisdiction);
			regionContext = immutableMap(regionContext);
			audience = immutableList(audience);
			dependencies = immutableList(dependencies);
			supportingUrls = supportingUrls == null ? List.of() : List.copyOf(supportingUrls);
			chunks = immutableList(chunks);
			relations = immutableList(relations);
		}
	}

	public record Chunk(int chunkOrder, String text) {
	}

	public record Relation(
		String subject,
		String predicate,
		String object,
		BigDecimal confidence,
		int evidenceChunkOrder
	) {
	}

	private static <T> List<T> immutableList(List<T> values) {
		return values == null ? null : List.copyOf(values);
	}

	private static <K, V> Map<K, V> immutableMap(Map<K, V> values) {
		return values == null ? null : Map.copyOf(values);
	}
}
