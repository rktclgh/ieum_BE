package shinhan.fibri.ieum.ai.knowledge.packageimport;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class KnowledgeSeedPersistencePlanFactory {

	private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

	private final ObjectMapper objectMapper;

	public KnowledgeSeedPersistencePlanFactory(ObjectMapper objectMapper) {
		this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null").copy();
	}

	public KnowledgeSeedPersistencePlan create(PreparedKnowledgeSeedPackage preparedPackage) {
		Objects.requireNonNull(preparedPackage, "preparedPackage must not be null");
		KnowledgeSeedPackage seedPackage = preparedPackage.seedPackage();
		List<KnowledgeSeedPersistencePlan.SourceRow> sourceRows = new ArrayList<>(
			preparedPackage.sources().size()
		);
		for (PreparedKnowledgeSeedPackage.PreparedSource preparedSource : preparedPackage.sources()) {
			sourceRows.add(sourceRow(seedPackage, preparedSource));
		}
		return new KnowledgeSeedPersistencePlan(
			seedPackage.packageKey(),
			seedPackage.packageVersion(),
			seedPackage.manifestHash(),
			seedPackage.expectedSourceCount(),
			sourceRows
		);
	}

	private KnowledgeSeedPersistencePlan.SourceRow sourceRow(
		KnowledgeSeedPackage seedPackage,
		PreparedKnowledgeSeedPackage.PreparedSource preparedSource
	) {
		KnowledgeSeedPackage.Source source = preparedSource.source();
		if (source.chunks() == null || source.chunks().size() != 1) {
			throw new IllegalArgumentException("source must contain exactly one chunk");
		}
		KnowledgeSeedPackage.Chunk chunk = source.chunks().getFirst();
		String externalRef = "seed:" + seedPackage.packageKey() + ':' + source.sourceKey();
		List<KnowledgeSeedPersistencePlan.RelationRow> relations = source.relations().stream()
			.map(relation -> relationRow(seedPackage, source, relation))
			.toList();
		return new KnowledgeSeedPersistencePlan.SourceRow(
			source.sourceKey(),
			externalRef,
			source.contentHash(),
			source.displayName(),
			source.geoScope(),
			json(source.regionContext()),
			validUntilExclusive(source.validUntil()),
			json(sourceMetadata(seedPackage, source)),
			new KnowledgeSeedPersistencePlan.ChunkRow(
				chunk.chunkOrder(),
				chunk.text(),
				json(chunkMetadata(seedPackage, source, chunk)),
				preparedSource.embedding()
			),
			relations
		);
	}

	private KnowledgeSeedPersistencePlan.RelationRow relationRow(
		KnowledgeSeedPackage seedPackage,
		KnowledgeSeedPackage.Source source,
		KnowledgeSeedPackage.Relation relation
	) {
		return new KnowledgeSeedPersistencePlan.RelationRow(
			relation.subject(),
			relation.predicate(),
			relation.object(),
			relation.confidence(),
			relation.evidenceChunkOrder(),
			json(relationMetadata(seedPackage, source, relation))
		);
	}

	private Map<String, Object> sourceMetadata(
		KnowledgeSeedPackage seedPackage,
		KnowledgeSeedPackage.Source source
	) {
		Map<String, Object> metadata = new LinkedHashMap<>();
		metadata.put("schemaVersion", seedPackage.schemaVersion());
		metadata.put("packageKey", seedPackage.packageKey());
		metadata.put("packageVersion", seedPackage.packageVersion());
		metadata.put("manifestHash", seedPackage.manifestHash());
		metadata.put("expectedSourceCount", seedPackage.expectedSourceCount());
		metadata.put("sourceKey", source.sourceKey());
		metadata.put("documentType", source.documentType());
		metadata.put("sourceGrade", source.sourceGrade());
		metadata.put("authorityLevel", source.authorityLevel());
		metadata.put("jurisdiction", source.jurisdiction());
		metadata.put("audience", source.audience());
		metadata.put("dependencies", source.dependencies());
		metadata.put("riskDomain", source.riskDomain());
		metadata.put("retrievedAt", source.retrievedAt());
		metadata.put("verifiedAt", source.verifiedAt());
		metadata.put("effectiveFrom", source.effectiveFrom());
		metadata.put("reviewIntervalDays", source.reviewIntervalDays());
		metadata.put("canonicalUrl", source.canonicalUrl());
		metadata.put("supportingUrls", source.supportingUrls());
		return metadata;
	}

	private Map<String, Object> chunkMetadata(
		KnowledgeSeedPackage seedPackage,
		KnowledgeSeedPackage.Source source,
		KnowledgeSeedPackage.Chunk chunk
	) {
		return Map.of(
			"packageVersion", seedPackage.packageVersion(),
			"sourceKey", source.sourceKey(),
			"chunkOrder", chunk.chunkOrder()
		);
	}

	private Map<String, Object> relationMetadata(
		KnowledgeSeedPackage seedPackage,
		KnowledgeSeedPackage.Source source,
		KnowledgeSeedPackage.Relation relation
	) {
		return Map.of(
			"packageVersion", seedPackage.packageVersion(),
			"sourceKey", source.sourceKey(),
			"evidenceChunkOrder", relation.evidenceChunkOrder()
		);
	}

	private OffsetDateTime validUntilExclusive(String value) {
		if (value == null) {
			return null;
		}
		return LocalDate.parse(value)
			.plusDays(1)
			.atStartOfDay(SEOUL)
			.toOffsetDateTime();
	}

	private String json(Object value) {
		try {
			return objectMapper.writeValueAsString(value);
		}
		catch (JsonProcessingException exception) {
			throw new IllegalStateException("knowledge seed persistence metadata cannot be serialized", exception);
		}
	}
}
