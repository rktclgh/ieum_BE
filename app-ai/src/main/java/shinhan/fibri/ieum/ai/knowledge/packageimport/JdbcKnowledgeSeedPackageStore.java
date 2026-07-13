package shinhan.fibri.ieum.ai.knowledge.packageimport;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.StringJoiner;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

public final class JdbcKnowledgeSeedPackageStore implements KnowledgeSeedPackageStore {

	private static final String ACTOR = "knowledge-importer";
	private static final String DEACTIVATION_REASON = "superseded_by_seed_package";
	private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

	private final JdbcClient jdbc;
	private final ObjectMapper objectMapper;
	private final TransactionTemplate transaction;

	public JdbcKnowledgeSeedPackageStore(
		JdbcClient jdbc,
		PlatformTransactionManager transactionManager,
		ObjectMapper objectMapper
	) {
		this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
		this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null").copy();
		this.transaction = new TransactionTemplate(
			Objects.requireNonNull(transactionManager, "transactionManager must not be null")
		);
		this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		this.transaction.setName("knowledge-seed-package-import");
	}

	@Override
	public KnowledgeSeedImportOutcome importPlan(KnowledgeSeedPersistencePlan plan) {
		Objects.requireNonNull(plan, "plan must not be null");
		return Objects.requireNonNull(
			transaction.execute(status -> importLocked(plan)),
			"knowledge seed import transaction returned no outcome"
		);
	}

	private KnowledgeSeedImportOutcome importLocked(KnowledgeSeedPersistencePlan plan) {
		lockPackage(plan.packageKey());
		assertPlanExternalReferences(plan);
		assertActivePackageIdentities(
			plan.packageKey(),
			lockActivePackageSources(plan.packageKey(), plan.sources())
		);
		List<StoredSource> existing = findPackageVersionSources(plan.packageKey(), plan.packageVersion());
		if (!existing.isEmpty()) {
			assertManifestHash(plan.manifestHash(), existing);
			if (!sameGraph(plan, existing)) {
				throw new KnowledgeSeedImportException(KnowledgeSeedImportException.Code.PACKAGE_PARTIAL_STATE);
			}
			return KnowledgeSeedImportOutcome.NO_OP;
		}

		deactivatePreviousRevisions(plan.packageKey());
		insertGraph(plan.sources());
		return KnowledgeSeedImportOutcome.IMPORTED;
	}

	private void lockPackage(String packageKey) {
		jdbc.sql("SELECT pg_advisory_xact_lock(:lockKey), 1 AS locked")
			.param("lockKey", advisoryLockKey(packageKey))
			.query((resultSet, rowNumber) -> resultSet.getInt("locked"))
			.single();
	}

	private void assertPlanExternalReferences(KnowledgeSeedPersistencePlan plan) {
		Map<String, String> sourceKeysByExternalRef = new HashMap<>();
		for (KnowledgeSeedPersistencePlan.SourceRow source : plan.sources()) {
			String expectedExternalRef = externalRef(plan.packageKey(), source.sourceKey());
			if (!expectedExternalRef.equals(source.externalRef())
				|| sourceKeysByExternalRef.put(source.externalRef(), source.sourceKey()) != null) {
				throw new IllegalArgumentException("knowledge seed persistence plan has an invalid external reference");
			}
		}
	}

	private List<ActiveSourceIdentity> lockActivePackageSources(
		String packageKey,
		List<KnowledgeSeedPersistencePlan.SourceRow> sources
	) {
		return jdbc.sql("""
			SELECT source_id,
			       external_ref,
			       metadata ->> 'packageKey' AS package_key,
			       metadata ->> 'packageVersion' AS package_version,
			       metadata ->> 'manifestHash' AS manifest_hash,
			       metadata ->> 'expectedSourceCount' AS expected_source_count,
			       metadata ->> 'sourceKey' AS source_key
			FROM knowledge_sources
			WHERE source_type = 'curated'
			  AND active
			  AND (
			      metadata ->> 'packageKey' = :packageKey
			      OR starts_with(external_ref, :externalRefPrefix)
			      OR external_ref IN (:externalRefs)
			  )
			ORDER BY source_id
			FOR UPDATE
			""")
			.param("packageKey", packageKey)
			.param("externalRefPrefix", externalRefPrefix(packageKey))
			.param("externalRefs", sources.stream()
				.map(KnowledgeSeedPersistencePlan.SourceRow::externalRef)
				.toList())
			.query((resultSet, rowNumber) -> new ActiveSourceIdentity(
				resultSet.getLong("source_id"),
				resultSet.getString("external_ref"),
				resultSet.getString("package_key"),
				resultSet.getString("package_version"),
				resultSet.getString("manifest_hash"),
				resultSet.getString("expected_source_count"),
				resultSet.getString("source_key")
			))
			.list();
	}

	private void assertActivePackageIdentities(String packageKey, List<ActiveSourceIdentity> activeSources) {
		String activeVersion = null;
		String activeManifestHash = null;
		Integer activeExpectedSourceCount = null;
		Set<String> activeSourceKeys = new HashSet<>();
		for (ActiveSourceIdentity source : activeSources) {
			Integer expectedSourceCount = positiveInteger(source.expectedSourceCount());
			if (!packageKey.equals(source.packageKey())
				|| source.packageVersion() == null
				|| source.packageVersion().isBlank()
				|| source.manifestHash() == null
				|| !SHA256.matcher(source.manifestHash()).matches()
				|| expectedSourceCount == null
				|| source.sourceKey() == null
				|| source.sourceKey().isBlank()
				|| !activeSourceKeys.add(source.sourceKey())
				|| !externalRef(packageKey, source.sourceKey()).equals(source.externalRef())) {
				throw new KnowledgeSeedImportException(KnowledgeSeedImportException.Code.PACKAGE_PARTIAL_STATE);
			}
			if (activeVersion == null) {
				activeVersion = source.packageVersion();
				activeManifestHash = source.manifestHash();
				activeExpectedSourceCount = expectedSourceCount;
			}
			else if (!activeVersion.equals(source.packageVersion())
				|| !activeManifestHash.equals(source.manifestHash())
				|| !activeExpectedSourceCount.equals(expectedSourceCount)) {
				throw new KnowledgeSeedImportException(KnowledgeSeedImportException.Code.PACKAGE_PARTIAL_STATE);
			}
		}
		if (activeExpectedSourceCount != null && activeExpectedSourceCount != activeSources.size()) {
			throw new KnowledgeSeedImportException(KnowledgeSeedImportException.Code.PACKAGE_PARTIAL_STATE);
		}
	}

	private Integer positiveInteger(String value) {
		try {
			int parsed = Integer.parseInt(value);
			return parsed > 0 ? parsed : null;
		}
		catch (NumberFormatException exception) {
			return null;
		}
	}

	private String externalRef(String packageKey, String sourceKey) {
		return externalRefPrefix(packageKey) + sourceKey;
	}

	private String externalRefPrefix(String packageKey) {
		return "seed:" + packageKey + ':';
	}

	private List<StoredSource> findPackageVersionSources(String packageKey, String packageVersion) {
		return jdbc.sql("""
			SELECT source_id,
			       question_id IS NULL AS question_is_null,
			       answer_id IS NULL AS answer_is_null,
			       external_ref,
			       btrim(content_hash) AS content_hash,
			       display_name,
			       status,
			       active,
			       geo_scope,
			       region_context::text AS region_context,
			       valid_until,
			       metadata::text AS metadata,
			       anchor_location IS NULL AS anchor_is_null,
			       created_by,
			       updated_by,
			       metadata ->> 'sourceKey' AS source_key,
			       metadata ->> 'manifestHash' AS manifest_hash
			FROM knowledge_sources
			WHERE source_type = 'curated'
			  AND metadata ->> 'packageKey' = :packageKey
			  AND metadata ->> 'packageVersion' = :packageVersion
			ORDER BY source_id
			FOR UPDATE
			""")
			.param("packageKey", packageKey)
			.param("packageVersion", packageVersion)
			.query(this::mapSource)
			.list();
	}

	private StoredSource mapSource(ResultSet resultSet, int rowNumber) throws SQLException {
		return new StoredSource(
			resultSet.getLong("source_id"),
			resultSet.getBoolean("question_is_null"),
			resultSet.getBoolean("answer_is_null"),
			resultSet.getString("external_ref"),
			resultSet.getString("content_hash"),
			resultSet.getString("display_name"),
			resultSet.getString("status"),
			resultSet.getBoolean("active"),
			resultSet.getString("geo_scope"),
			resultSet.getString("region_context"),
			resultSet.getObject("valid_until", OffsetDateTime.class),
			resultSet.getString("metadata"),
			resultSet.getBoolean("anchor_is_null"),
			resultSet.getString("created_by"),
			resultSet.getString("updated_by"),
			resultSet.getString("source_key"),
			resultSet.getString("manifest_hash")
		);
	}

	private void assertManifestHash(String manifestHash, List<StoredSource> existing) {
		boolean missingHash = existing.stream().anyMatch(source -> source.manifestHash() == null);
		if (missingHash) {
			throw new KnowledgeSeedImportException(KnowledgeSeedImportException.Code.PACKAGE_PARTIAL_STATE);
		}
		boolean conflictingHash = existing.stream()
			.anyMatch(source -> !manifestHash.equals(source.manifestHash()));
		if (conflictingHash) {
			throw new KnowledgeSeedImportException(
				KnowledgeSeedImportException.Code.PACKAGE_VERSION_HASH_CONFLICT
			);
		}
	}

	private boolean sameGraph(KnowledgeSeedPersistencePlan plan, List<StoredSource> existing) {
		if (existing.size() != plan.expectedSourceCount()) {
			return false;
		}
		Map<String, StoredSource> storedBySourceKey = uniqueSourcesByKey(existing);
		if (storedBySourceKey.size() != plan.expectedSourceCount()) {
			return false;
		}

		List<Long> sourceIds = existing.stream().map(StoredSource::sourceId).toList();
		Map<Long, List<StoredChunk>> chunksBySource = groupChunks(findChunks(sourceIds));
		Map<Long, List<StoredRelation>> relationsBySource = groupRelations(findRelations(sourceIds));
		for (KnowledgeSeedPersistencePlan.SourceRow source : plan.sources()) {
			StoredSource stored = storedBySourceKey.remove(source.sourceKey());
			if (stored == null || !sameSource(source, stored)) {
				return false;
			}
			List<StoredChunk> chunks = chunksBySource.getOrDefault(stored.sourceId(), List.of());
			if (chunks.size() != 1 || !sameChunk(source.chunk(), chunks.getFirst())) {
				return false;
			}
			if (!sameRelations(
				source.relations(),
				relationsBySource.getOrDefault(stored.sourceId(), List.of())
			)) {
				return false;
			}
		}
		return storedBySourceKey.isEmpty();
	}

	private Map<String, StoredSource> uniqueSourcesByKey(List<StoredSource> sources) {
		Map<String, StoredSource> result = new HashMap<>();
		for (StoredSource source : sources) {
			if (source.sourceKey() == null || result.put(source.sourceKey(), source) != null) {
				return Map.of();
			}
		}
		return result;
	}

	private boolean sameSource(KnowledgeSeedPersistencePlan.SourceRow expected, StoredSource actual) {
		return actual.questionIsNull()
			&& actual.answerIsNull()
			&& actual.anchorIsNull()
			&& actual.active()
			&& "ready".equals(actual.status())
			&& ACTOR.equals(actual.createdBy())
			&& ACTOR.equals(actual.updatedBy())
			&& expected.externalRef().equals(actual.externalRef())
			&& expected.contentHash().equals(actual.contentHash())
			&& expected.displayName().equals(actual.displayName())
			&& expected.geoScope().equals(actual.geoScope())
			&& sameJson(expected.regionContextJson(), actual.regionContextJson())
			&& sameInstant(expected.validUntilExclusive(), actual.validUntil())
			&& sameJson(expected.metadataJson(), actual.metadataJson());
	}

	private List<StoredChunk> findChunks(List<Long> sourceIds) {
		return jdbc.sql("""
			SELECT source_id,
			       chunk_id,
			       content,
			       chunk_order,
			       metadata::text AS metadata,
			       embedding_model,
			       vector_dims(embedding) AS vector_dimensions
			FROM knowledge_chunks
			WHERE source_id IN (:sourceIds)
			ORDER BY source_id, chunk_order, chunk_id
			""")
			.param("sourceIds", sourceIds)
			.query((resultSet, rowNumber) -> new StoredChunk(
				resultSet.getLong("source_id"),
				resultSet.getLong("chunk_id"),
				resultSet.getString("content"),
				resultSet.getInt("chunk_order"),
				resultSet.getString("metadata"),
				resultSet.getString("embedding_model"),
				resultSet.getInt("vector_dimensions")
			))
			.list();
	}

	private Map<Long, List<StoredChunk>> groupChunks(List<StoredChunk> chunks) {
		Map<Long, List<StoredChunk>> result = new HashMap<>();
		for (StoredChunk chunk : chunks) {
			result.computeIfAbsent(chunk.sourceId(), ignored -> new ArrayList<>()).add(chunk);
		}
		return result;
	}

	private boolean sameChunk(KnowledgeSeedPersistencePlan.ChunkRow expected, StoredChunk actual) {
		return expected.chunkOrder() == actual.chunkOrder()
			&& expected.content().equals(actual.content())
			&& sameJson(expected.metadataJson(), actual.metadataJson())
			&& expected.embedding().model().equals(actual.embeddingModel())
			&& actual.vectorDimensions() == expected.embedding().values().size();
	}

	private List<StoredRelation> findRelations(List<Long> sourceIds) {
		return jdbc.sql("""
			SELECT kr.source_id,
			       kr.subject,
			       kr.predicate,
			       kr.object,
			       kr.confidence,
			       kr.evidence_chunk_id,
			       kc.chunk_order AS evidence_chunk_order,
			       kr.metadata::text AS metadata
			FROM knowledge_relations kr
			LEFT JOIN knowledge_chunks kc
			  ON kc.source_id = kr.source_id
			 AND kc.chunk_id = kr.evidence_chunk_id
			WHERE kr.source_id IN (:sourceIds)
			ORDER BY kr.source_id, kr.relation_id
			""")
			.param("sourceIds", sourceIds)
			.query(this::mapRelation)
			.list();
	}

	private StoredRelation mapRelation(ResultSet resultSet, int rowNumber) throws SQLException {
		Integer evidenceChunkOrder = resultSet.getObject("evidence_chunk_order", Integer.class);
		return new StoredRelation(
			resultSet.getLong("source_id"),
			resultSet.getString("subject"),
			resultSet.getString("predicate"),
			resultSet.getString("object"),
			resultSet.getBigDecimal("confidence"),
			resultSet.getObject("evidence_chunk_id", Long.class),
			evidenceChunkOrder,
			resultSet.getString("metadata")
		);
	}

	private Map<Long, List<StoredRelation>> groupRelations(List<StoredRelation> relations) {
		Map<Long, List<StoredRelation>> result = new HashMap<>();
		for (StoredRelation relation : relations) {
			result.computeIfAbsent(relation.sourceId(), ignored -> new ArrayList<>()).add(relation);
		}
		return result;
	}

	private boolean sameRelations(
		List<KnowledgeSeedPersistencePlan.RelationRow> expected,
		List<StoredRelation> actual
	) {
		if (expected.size() != actual.size()) {
			return false;
		}
		Map<RelationKey, StoredRelation> storedByKey = new HashMap<>();
		for (StoredRelation relation : actual) {
			RelationKey key = new RelationKey(relation.subject(), relation.predicate(), relation.object());
			if (storedByKey.put(key, relation) != null) {
				return false;
			}
		}
		for (KnowledgeSeedPersistencePlan.RelationRow relation : expected) {
			StoredRelation stored = storedByKey.remove(
				new RelationKey(relation.subject(), relation.predicate(), relation.object())
			);
			if (stored == null
				|| stored.evidenceChunkId() == null
				|| stored.evidenceChunkOrder() == null
				|| relation.evidenceChunkOrder() != stored.evidenceChunkOrder()
				|| normalizedConfidence(relation.confidence()).compareTo(stored.confidence()) != 0
				|| !sameJson(relation.metadataJson(), stored.metadataJson())) {
				return false;
			}
		}
		return storedByKey.isEmpty();
	}

	private BigDecimal normalizedConfidence(BigDecimal value) {
		return value.setScale(4, RoundingMode.HALF_UP);
	}

	private boolean sameInstant(OffsetDateTime expected, OffsetDateTime actual) {
		Instant expectedInstant = expected == null ? null : expected.toInstant();
		Instant actualInstant = actual == null ? null : actual.toInstant();
		return Objects.equals(expectedInstant, actualInstant);
	}

	private boolean sameJson(String expected, String actual) {
		return parseJson(expected).equals(parseJson(actual));
	}

	private JsonNode parseJson(String value) {
		try {
			return objectMapper.readTree(value);
		}
		catch (JsonProcessingException exception) {
			throw new IllegalArgumentException("knowledge seed persistence plan contains invalid JSON", exception);
		}
	}

	private void deactivatePreviousRevisions(String packageKey) {
		jdbc.sql("""
			UPDATE knowledge_sources
			SET status = 'inactive',
			    deactivation_reason = :deactivationReason,
			    updated_by = :actor
			WHERE source_type = 'curated'
			  AND active
			  AND (
			      metadata ->> 'packageKey' = :packageKey
			      OR starts_with(external_ref, :externalRefPrefix)
			  )
			""")
			.param("deactivationReason", DEACTIVATION_REASON)
			.param("actor", ACTOR)
			.param("packageKey", packageKey)
			.param("externalRefPrefix", externalRefPrefix(packageKey))
			.update();
	}

	private void insertGraph(List<KnowledgeSeedPersistencePlan.SourceRow> sources) {
		for (KnowledgeSeedPersistencePlan.SourceRow source : sources) {
			long sourceId = insertSource(source);
			long chunkId = insertChunk(sourceId, source.chunk());
			for (KnowledgeSeedPersistencePlan.RelationRow relation : source.relations()) {
				insertRelation(sourceId, chunkId, relation);
			}
		}
	}

	private long insertSource(KnowledgeSeedPersistencePlan.SourceRow source) {
		return jdbc.sql("""
			INSERT INTO knowledge_sources (
			    source_type,
			    external_ref,
			    content_hash,
			    display_name,
			    status,
			    geo_scope,
			    region_context,
			    valid_until,
			    metadata,
			    created_by,
			    updated_by
			)
			VALUES (
			    'curated',
			    :externalRef,
			    :contentHash,
			    :displayName,
			    'ready',
			    :geoScope,
			    CAST(:regionContext AS jsonb),
			    CAST(:validUntil AS timestamptz),
			    CAST(:metadata AS jsonb),
			    :actor,
			    :actor
			)
			RETURNING source_id
			""")
			.param("externalRef", source.externalRef())
			.param("contentHash", source.contentHash())
			.param("displayName", source.displayName())
			.param("geoScope", source.geoScope())
			.param("regionContext", source.regionContextJson())
			.param("validUntil", source.validUntilExclusive(), Types.TIMESTAMP_WITH_TIMEZONE)
			.param("metadata", source.metadataJson())
			.param("actor", ACTOR)
			.query(Long.class)
			.single();
	}

	private long insertChunk(long sourceId, KnowledgeSeedPersistencePlan.ChunkRow chunk) {
		return jdbc.sql("""
			INSERT INTO knowledge_chunks (
			    source_id,
			    content,
			    chunk_order,
			    metadata,
			    embedding,
			    embedding_model
			)
			VALUES (
			    :sourceId,
			    :content,
			    :chunkOrder,
			    CAST(:metadata AS jsonb),
			    CAST(:embedding AS vector),
			    :embeddingModel
			)
			RETURNING chunk_id
			""")
			.param("sourceId", sourceId)
			.param("content", chunk.content())
			.param("chunkOrder", chunk.chunkOrder())
			.param("metadata", chunk.metadataJson())
			.param("embedding", vectorLiteral(chunk.embedding().values()))
			.param("embeddingModel", chunk.embedding().model())
			.query(Long.class)
			.single();
	}

	private void insertRelation(
		long sourceId,
		long chunkId,
		KnowledgeSeedPersistencePlan.RelationRow relation
	) {
		jdbc.sql("""
			INSERT INTO knowledge_relations (
			    source_id,
			    subject,
			    predicate,
			    object,
			    confidence,
			    evidence_chunk_id,
			    metadata
			)
			VALUES (
			    :sourceId,
			    :subject,
			    :predicate,
			    :object,
			    :confidence,
			    :evidenceChunkId,
			    CAST(:metadata AS jsonb)
			)
			""")
			.param("sourceId", sourceId)
			.param("subject", relation.subject())
			.param("predicate", relation.predicate())
			.param("object", relation.object())
			.param("confidence", relation.confidence())
			.param("evidenceChunkId", chunkId)
			.param("metadata", relation.metadataJson())
			.update();
	}

	private String vectorLiteral(List<Float> values) {
		StringJoiner literal = new StringJoiner(",", "[", "]");
		for (Float value : values) {
			literal.add(Float.toString(value));
		}
		return literal.toString();
	}

	private long advisoryLockKey(String packageKey) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
				.digest(("ieum:seed-package:" + packageKey).getBytes(StandardCharsets.UTF_8));
			return ByteBuffer.wrap(digest).getLong();
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	private record StoredSource(
		long sourceId,
		boolean questionIsNull,
		boolean answerIsNull,
		String externalRef,
		String contentHash,
		String displayName,
		String status,
		boolean active,
		String geoScope,
		String regionContextJson,
		OffsetDateTime validUntil,
		String metadataJson,
		boolean anchorIsNull,
		String createdBy,
		String updatedBy,
		String sourceKey,
		String manifestHash
	) {
	}

	private record ActiveSourceIdentity(
		long sourceId,
		String externalRef,
		String packageKey,
		String packageVersion,
		String manifestHash,
		String expectedSourceCount,
		String sourceKey
	) {
	}

	private record StoredChunk(
		long sourceId,
		long chunkId,
		String content,
		int chunkOrder,
		String metadataJson,
		String embeddingModel,
		int vectorDimensions
	) {
	}

	private record StoredRelation(
		long sourceId,
		String subject,
		String predicate,
		String object,
		BigDecimal confidence,
		Long evidenceChunkId,
		Integer evidenceChunkOrder,
		String metadataJson
	) {
	}

	private record RelationKey(String subject, String predicate, String object) {
	}
}
