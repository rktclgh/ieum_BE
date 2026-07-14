package shinhan.fibri.ieum.ai.knowledge.packageimport;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import shinhan.fibri.ieum.ai.embedding.GeminiEmbedding;

public record KnowledgeSeedPersistencePlan(
	String packageKey,
	String packageVersion,
	String manifestHash,
	int expectedSourceCount,
	List<SourceRow> sources
) {

	public KnowledgeSeedPersistencePlan {
		packageKey = required(packageKey, "packageKey");
		packageVersion = required(packageVersion, "packageVersion");
		manifestHash = required(manifestHash, "manifestHash");
		if (expectedSourceCount <= 0) {
			throw new IllegalArgumentException("expectedSourceCount must be positive");
		}
		Objects.requireNonNull(sources, "sources must not be null");
		sources = List.copyOf(sources);
		if (sources.size() != expectedSourceCount) {
			throw new IllegalArgumentException("sources must match expectedSourceCount");
		}
	}

	public record SourceRow(
		String sourceKey,
		String externalRef,
		String contentHash,
		String displayName,
		String geoScope,
		String regionContextJson,
		OffsetDateTime validUntilExclusive,
		String metadataJson,
		ChunkRow chunk,
		List<RelationRow> relations
	) {

		public SourceRow {
			sourceKey = required(sourceKey, "sourceKey");
			externalRef = required(externalRef, "externalRef");
			contentHash = required(contentHash, "contentHash");
			displayName = required(displayName, "displayName");
			geoScope = required(geoScope, "geoScope");
			regionContextJson = required(regionContextJson, "regionContextJson");
			metadataJson = required(metadataJson, "metadataJson");
			Objects.requireNonNull(chunk, "chunk must not be null");
			Objects.requireNonNull(relations, "relations must not be null");
			relations = List.copyOf(relations);
		}
	}

	public record ChunkRow(
		int chunkOrder,
		String content,
		String metadataJson,
		GeminiEmbedding embedding
	) {

		public ChunkRow {
			if (chunkOrder != 0) {
				throw new IllegalArgumentException("chunkOrder must be 0");
			}
			content = required(content, "content");
			metadataJson = required(metadataJson, "metadataJson");
			Objects.requireNonNull(embedding, "embedding must not be null");
		}
	}

	public record RelationRow(
		String subject,
		String predicate,
		String object,
		BigDecimal confidence,
		int evidenceChunkOrder,
		String metadataJson
	) {

		public RelationRow {
			subject = required(subject, "subject");
			predicate = required(predicate, "predicate");
			object = required(object, "object");
			Objects.requireNonNull(confidence, "confidence must not be null");
			if (evidenceChunkOrder != 0) {
				throw new IllegalArgumentException("evidenceChunkOrder must be 0");
			}
			metadataJson = required(metadataJson, "metadataJson");
		}
	}

	private static String required(String value, String field) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(field + " must not be blank");
		}
		return value;
	}
}
