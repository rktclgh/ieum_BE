package shinhan.fibri.ieum.ai.knowledge.accepted;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import shinhan.fibri.ieum.ai.question.analysis.GeoScope;
import shinhan.fibri.ieum.ai.question.analysis.StoredLocationSnapshot;

public final class JdbcAcceptedAnswerKnowledgeRepository implements AcceptedAnswerKnowledgeRepository {

	private static final String ACTOR = "accepted-answer-ingestion";
	private static final String INGESTION_VERSION = "accepted-answer-v1";
	private static final String SOURCE_GRADE = "community";
	private static final String INELIGIBLE_DISPLAY_NAME = "채택된 답변";
	private static final String INELIGIBLE_REASON = "ineligible_text";
	private static final String BECAME_INELIGIBLE_REASON = "source_became_ineligible";
	private static final String FAILURE_CODE = "accepted_answer_embedding_failed";
	private static final String FAILURE_MESSAGE = "Accepted answer embedding failed";
	private static final int EMBEDDING_DIMENSIONS = 768;

	private final JdbcClient jdbc;
	private final TransactionTemplate transaction;
	private final AcceptedAnswerKnowledgeDocumentFactory documentFactory;

	public JdbcAcceptedAnswerKnowledgeRepository(
		JdbcClient jdbc,
		PlatformTransactionManager transactionManager,
		AcceptedAnswerKnowledgeDocumentFactory documentFactory
	) {
		this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
		this.documentFactory = Objects.requireNonNull(documentFactory, "documentFactory must not be null");
		this.transaction = new TransactionTemplate(
			Objects.requireNonNull(transactionManager, "transactionManager must not be null")
		);
		this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		this.transaction.setName("accepted-answer-knowledge");
	}

	@Override
	public Optional<AcceptedAnswerKnowledgeClaim> claimByAnswerId(
		long answerId,
		Duration lease,
		int maxAttempts
	) {
		validateAnswerId(answerId);
		validatePositiveDuration(lease, "lease");
		validateMaxAttempts(maxAttempts);
		return Objects.requireNonNull(
			transaction.execute(status -> claimInTransaction(answerId, lease, maxAttempts)),
			"accepted answer claim transaction returned null"
		);
	}

	@Override
	public AcceptedAnswerKnowledgeFinalizeResult finalizeClaim(
		AcceptedAnswerKnowledgeClaim claim,
		List<Float> embedding
	) {
		Objects.requireNonNull(claim, "claim must not be null");
		String vector = vectorLiteral(embedding);
		return Objects.requireNonNull(
			transaction.execute(status -> finalizeInTransaction(claim, vector)),
			"accepted answer finalize transaction returned null"
		);
	}

	@Override
	public boolean markEmbeddingFailure(
		AcceptedAnswerKnowledgeClaim claim,
		Duration retryDelay,
		int maxAttempts
	) {
		Objects.requireNonNull(claim, "claim must not be null");
		validatePositiveDuration(retryDelay, "retryDelay");
		validateMaxAttempts(maxAttempts);
		Boolean updated = transaction.execute(status -> jdbc.sql("""
			UPDATE knowledge_sources
			SET status = 'failed',
			    ingestion_token = NULL,
			    ingestion_lease_until = NULL,
			    next_attempt_at = CASE
			        WHEN ingestion_attempts >= :maxAttempts THEN NULL
			        ELSE clock_timestamp() + CAST(:retryMillis || ' milliseconds' AS interval)
			    END,
			    last_error_code = :errorCode,
			    last_error_message = :errorMessage,
			    updated_by = :actor
			WHERE source_id = :sourceId
			  AND question_id = :questionId
			  AND answer_id = :answerId
			  AND status = 'pending'
			  AND ingestion_token = :token
			  AND ingestion_lease_until = :leaseUntil
			  AND ingestion_lease_until > clock_timestamp()
			""")
			.param("maxAttempts", maxAttempts)
			.param("retryMillis", retryDelay.toMillis())
			.param("errorCode", FAILURE_CODE)
			.param("errorMessage", FAILURE_MESSAGE)
			.param("actor", ACTOR)
			.param("sourceId", claim.sourceId())
			.param("questionId", claim.questionId())
			.param("answerId", claim.answerId())
			.param("token", claim.ingestionToken())
			.param("leaseUntil", claim.leaseUntil())
			.update() == 1);
		return Boolean.TRUE.equals(updated);
	}

	private Optional<AcceptedAnswerKnowledgeClaim> claimInTransaction(
		long answerId,
		Duration lease,
		int maxAttempts
	) {
		Optional<EligibleSnapshot> snapshot = findEligibleSnapshot(answerId);
		if (snapshot.isEmpty()) {
			return Optional.empty();
		}
		Optional<StoredSource> stored = findStoredSourceForUpdate(answerId);
		Optional<AcceptedAnswerKnowledgeDocument> document = documentFactory.create(snapshot.get().snapshot());
		if (document.isEmpty()) {
			persistIneligibleMarker(snapshot.get(), stored);
			return Optional.empty();
		}

		OffsetDateTime now = databaseNow();
		OffsetDateTime requestedLeaseUntil = now.plus(lease);
		UUID token = UUID.randomUUID();
		if (stored.isPresent()) {
			StoredSource source = stored.get();
			if (!isReclaimable(source, now, maxAttempts)) {
				return Optional.empty();
			}
			return reclaim(snapshot.get(), source, document.get(), token, requestedLeaseUntil);
		}
		return insertFreshClaim(snapshot.get(), document.get(), token, requestedLeaseUntil);
	}

	private Optional<EligibleSnapshot> findEligibleSnapshot(long answerId) {
		return jdbc.sql("""
			SELECT a.answer_id,
			       a.question_id,
			       q.title,
			       q.content AS question_content,
			       a.content AS answer_content,
			       ST_Y(p.location::geometry) AS latitude,
			       ST_X(p.location::geometry) AS longitude,
			       p.address,
			       p.detail_address,
			       p.label,
			       qt.geo_scope
			FROM answers a
			JOIN questions q ON q.question_id = a.question_id
			JOIN pins p ON p.pin_id = q.pin_id
			LEFT JOIN ai_question_tasks qt ON qt.question_id = q.question_id
			WHERE a.answer_id = :answerId
			  AND a.is_accepted
			  AND NOT a.is_ai
			  AND a.author_id IS NOT NULL
			  AND q.deleted_at IS NULL
			  AND p.deleted_at IS NULL
			  AND p.pin_type = 'question'
			""")
			.param("answerId", answerId)
			.query(this::mapEligibleSnapshot)
			.optional();
	}

	private EligibleSnapshot mapEligibleSnapshot(ResultSet resultSet, int rowNumber) throws SQLException {
		GeoScope persistedGeoScope = resultSet.getString("geo_scope") == null
			? null
			: GeoScope.valueOf(resultSet.getString("geo_scope"));
		return new EligibleSnapshot(
			resultSet.getLong("question_id"),
			resultSet.getLong("answer_id"),
			new AcceptedAnswerKnowledgeSnapshot(
				resultSet.getString("title"),
				resultSet.getString("question_content"),
				resultSet.getString("answer_content"),
				new StoredLocationSnapshot(
					resultSet.getDouble("latitude"),
					resultSet.getDouble("longitude"),
					resultSet.getString("address"),
					resultSet.getString("detail_address"),
					resultSet.getString("label")
				),
				persistedGeoScope
			)
		);
	}

	private Optional<StoredSource> findStoredSourceForUpdate(long answerId) {
		return jdbc.sql("""
			SELECT source_id, status, ingestion_attempts, next_attempt_at, ingestion_lease_until
			FROM knowledge_sources
			WHERE answer_id = :answerId
			FOR UPDATE
			""")
			.param("answerId", answerId)
			.query((resultSet, rowNumber) -> new StoredSource(
				resultSet.getLong("source_id"),
				resultSet.getString("status"),
				resultSet.getInt("ingestion_attempts"),
				resultSet.getObject("next_attempt_at", OffsetDateTime.class),
				resultSet.getObject("ingestion_lease_until", OffsetDateTime.class)
			))
			.optional();
	}

	private void persistIneligibleMarker(
		EligibleSnapshot snapshot,
		Optional<StoredSource> stored
	) {
		String contentHash = sha256(INGESTION_VERSION + ":ineligible:" + snapshot.answerId());
		if (stored.isPresent()) {
			StoredSource source = stored.get();
			if ("admin_suppressed".equals(source.status())) {
				return;
			}
			jdbc.sql("""
				UPDATE knowledge_sources
				SET content_hash = :contentHash,
				    display_name = :displayName,
				    status = 'inactive',
				    deactivation_reason = :reason,
				    ingestion_token = NULL,
				    ingestion_lease_until = NULL,
				    next_attempt_at = NULL,
				    last_error_code = NULL,
				    last_error_message = NULL,
				    updated_by = :actor
				WHERE source_id = :sourceId
				""")
				.param("contentHash", contentHash)
				.param("displayName", INELIGIBLE_DISPLAY_NAME)
				.param("reason", INELIGIBLE_REASON)
				.param("actor", ACTOR)
				.param("sourceId", source.sourceId())
				.update();
			return;
		}
		jdbc.sql("""
			INSERT INTO knowledge_sources(
			    source_type, question_id, answer_id, content_hash, display_name,
			    status, deactivation_reason, ingestion_attempts, geo_scope,
			    region_context, metadata, created_by, updated_by
			)
			VALUES (
			    'accepted_human_answer', :questionId, :answerId, :contentHash, :displayName,
			    'inactive', :reason, 0, 'general', '{}',
			    jsonb_build_object('sourceGrade', :sourceGrade, 'ingestionVersion', :ingestionVersion),
			    :actor, :actor
			)
			ON CONFLICT DO NOTHING
			""")
			.param("questionId", snapshot.questionId())
			.param("answerId", snapshot.answerId())
			.param("contentHash", contentHash)
			.param("displayName", INELIGIBLE_DISPLAY_NAME)
			.param("reason", INELIGIBLE_REASON)
			.param("sourceGrade", SOURCE_GRADE)
			.param("ingestionVersion", INGESTION_VERSION)
			.param("actor", ACTOR)
			.update();
	}

	private boolean isReclaimable(StoredSource source, OffsetDateTime now, int maxAttempts) {
		if (source.attempts() >= maxAttempts) {
			return false;
		}
		if ("pending".equals(source.status())) {
			return source.leaseUntil() != null && !source.leaseUntil().isAfter(now);
		}
		if ("failed".equals(source.status())) {
			return source.nextAttemptAt() != null && !source.nextAttemptAt().isAfter(now);
		}
		return false;
	}

	private Optional<AcceptedAnswerKnowledgeClaim> reclaim(
		EligibleSnapshot snapshot,
		StoredSource source,
		AcceptedAnswerKnowledgeDocument document,
		UUID token,
		OffsetDateTime leaseUntil
	) {
		return jdbc.sql("""
			UPDATE knowledge_sources
			SET question_id = :questionId,
			    content_hash = :contentHash,
			    display_name = :displayName,
			    status = 'pending',
			    deactivation_reason = NULL,
			    ingestion_token = :token,
			    ingestion_lease_until = :leaseUntil,
			    ingestion_attempts = ingestion_attempts + 1,
			    next_attempt_at = NULL,
			    last_error_code = NULL,
			    last_error_message = NULL,
			    geo_scope = :geoScope,
			    region_context = jsonb_strip_nulls(jsonb_build_object(
			        'country', CAST(:country AS text), 'sido', CAST(:sido AS text),
			        'sigungu', CAST(:sigungu AS text), 'eupMyeonDong', CAST(:eupMyeonDong AS text),
			        'place', CAST(:place AS text)
			    )),
			    anchor_location = ST_SetSRID(ST_MakePoint(:longitude, :latitude), 4326)::geography,
			    metadata = jsonb_build_object(
			        'sourceGrade', :sourceGrade, 'ingestionVersion', :ingestionVersion
			    ),
			    updated_by = :actor
			WHERE source_id = :sourceId
			RETURNING ingestion_lease_until, ingestion_attempts
			""")
			.param("sourceId", source.sourceId())
			.param("questionId", snapshot.questionId())
			.param("contentHash", document.contentHash())
			.param("displayName", document.displayName())
			.param("token", token)
			.param("leaseUntil", leaseUntil)
			.param("geoScope", document.geoScope().name())
			.param("country", document.regionContext().country())
			.param("sido", document.regionContext().sido())
			.param("sigungu", document.regionContext().sigungu())
			.param("eupMyeonDong", document.regionContext().eupMyeonDong())
			.param("place", document.regionContext().place())
			.param("longitude", document.anchorLongitude())
			.param("latitude", document.anchorLatitude())
			.param("sourceGrade", SOURCE_GRADE)
			.param("ingestionVersion", INGESTION_VERSION)
			.param("actor", ACTOR)
			.query((resultSet, rowNumber) -> new AcceptedAnswerKnowledgeClaim(
				source.sourceId(), snapshot.questionId(), snapshot.answerId(), token,
				resultSet.getObject("ingestion_lease_until", OffsetDateTime.class),
				resultSet.getInt("ingestion_attempts"), document
			))
			.optional();
	}

	private Optional<AcceptedAnswerKnowledgeClaim> insertFreshClaim(
		EligibleSnapshot snapshot,
		AcceptedAnswerKnowledgeDocument document,
		UUID token,
		OffsetDateTime leaseUntil
	) {
		return jdbc.sql("""
			INSERT INTO knowledge_sources(
			    source_type, question_id, answer_id, content_hash, display_name, status,
			    ingestion_token, ingestion_lease_until, ingestion_attempts, geo_scope,
			    region_context, anchor_location, metadata, created_by, updated_by
			)
			VALUES (
			    'accepted_human_answer', :questionId, :answerId, :contentHash, :displayName, 'pending',
			    :token, :leaseUntil, 1, :geoScope,
			    jsonb_strip_nulls(jsonb_build_object(
			        'country', CAST(:country AS text), 'sido', CAST(:sido AS text),
			        'sigungu', CAST(:sigungu AS text), 'eupMyeonDong', CAST(:eupMyeonDong AS text),
			        'place', CAST(:place AS text)
			    )),
			    ST_SetSRID(ST_MakePoint(:longitude, :latitude), 4326)::geography,
			    jsonb_build_object('sourceGrade', :sourceGrade, 'ingestionVersion', :ingestionVersion),
			    :actor, :actor
			)
			ON CONFLICT DO NOTHING
			RETURNING source_id, ingestion_lease_until, ingestion_attempts
			""")
			.param("questionId", snapshot.questionId())
			.param("answerId", snapshot.answerId())
			.param("contentHash", document.contentHash())
			.param("displayName", document.displayName())
			.param("token", token)
			.param("leaseUntil", leaseUntil)
			.param("geoScope", document.geoScope().name())
			.param("country", document.regionContext().country())
			.param("sido", document.regionContext().sido())
			.param("sigungu", document.regionContext().sigungu())
			.param("eupMyeonDong", document.regionContext().eupMyeonDong())
			.param("place", document.regionContext().place())
			.param("longitude", document.anchorLongitude())
			.param("latitude", document.anchorLatitude())
			.param("sourceGrade", SOURCE_GRADE)
			.param("ingestionVersion", INGESTION_VERSION)
			.param("actor", ACTOR)
			.query((resultSet, rowNumber) -> new AcceptedAnswerKnowledgeClaim(
				resultSet.getLong("source_id"), snapshot.questionId(), snapshot.answerId(), token,
				resultSet.getObject("ingestion_lease_until", OffsetDateTime.class),
				resultSet.getInt("ingestion_attempts"), document
			))
			.optional();
	}

	private AcceptedAnswerKnowledgeFinalizeResult finalizeInTransaction(
		AcceptedAnswerKnowledgeClaim claim,
		String vector
	) {
		Optional<Long> fencedSource = jdbc.sql("""
			SELECT source_id
			FROM knowledge_sources
			WHERE source_id = :sourceId
			  AND question_id = :questionId
			  AND answer_id = :answerId
			  AND status = 'pending'
			  AND ingestion_token = :token
			  AND ingestion_lease_until = :leaseUntil
			  AND ingestion_lease_until > clock_timestamp()
			FOR UPDATE
			""")
			.param("sourceId", claim.sourceId())
			.param("questionId", claim.questionId())
			.param("answerId", claim.answerId())
			.param("token", claim.ingestionToken())
			.param("leaseUntil", claim.leaseUntil())
			.query(Long.class)
			.optional();
		if (fencedSource.isEmpty()) {
			return AcceptedAnswerKnowledgeFinalizeResult.STALE;
		}

		boolean eligible = jdbc.sql("""
			SELECT public.ai_lock_eligible_accepted_answer(:answerId)
			   AND EXISTS (
			       SELECT 1 FROM answers a
			       WHERE a.answer_id = :answerId AND a.question_id = :questionId
			   )
			""")
			.param("answerId", claim.answerId())
			.param("questionId", claim.questionId())
			.query(Boolean.class)
			.single();
		if (!eligible) {
			int deactivated = jdbc.sql("""
				UPDATE knowledge_sources
				SET status = 'inactive',
				    deactivation_reason = :reason,
				    ingestion_token = NULL,
				    ingestion_lease_until = NULL,
				    next_attempt_at = NULL,
				    last_error_code = NULL,
				    last_error_message = NULL,
				    updated_by = :actor
				WHERE source_id = :sourceId
				  AND question_id = :questionId
				  AND answer_id = :answerId
				  AND status = 'pending'
				  AND ingestion_token = :token
				  AND ingestion_lease_until = :leaseUntil
				  AND ingestion_lease_until > clock_timestamp()
				""")
				.param("reason", BECAME_INELIGIBLE_REASON)
				.param("actor", ACTOR)
				.param("sourceId", claim.sourceId())
				.param("questionId", claim.questionId())
				.param("answerId", claim.answerId())
				.param("token", claim.ingestionToken())
				.param("leaseUntil", claim.leaseUntil())
				.update();
			return deactivated == 1
				? AcceptedAnswerKnowledgeFinalizeResult.INELIGIBLE
				: AcceptedAnswerKnowledgeFinalizeResult.STALE;
		}

		Optional<Long> publishedSource = jdbc.sql("""
			WITH inserted_chunk AS (
			    INSERT INTO knowledge_chunks(
			        source_id, content, chunk_order, metadata, embedding, embedding_model
			    )
			    SELECT ks.source_id,
			           :content,
			           0,
			           jsonb_build_object(
			               'sourceType', 'accepted_human_answer', 'answerId', :answerId
			           ),
			           CAST(:embedding AS vector),
			           'gemini-embedding-2'
			    FROM knowledge_sources ks
			    WHERE ks.source_id = :sourceId
			      AND ks.question_id = :questionId
			      AND ks.answer_id = :answerId
			      AND ks.status = 'pending'
			      AND ks.ingestion_token = :token
			      AND ks.ingestion_lease_until = :leaseUntil
			      AND ks.ingestion_lease_until > clock_timestamp()
			    RETURNING source_id
			)
			UPDATE knowledge_sources ks
			SET status = 'ready',
			    ingestion_token = NULL,
			    ingestion_lease_until = NULL,
			    next_attempt_at = NULL,
			    last_error_code = NULL,
			    last_error_message = NULL,
			    deactivation_reason = NULL,
			    updated_by = :actor
			FROM inserted_chunk inserted
			WHERE ks.source_id = inserted.source_id
			RETURNING ks.source_id
			""")
			.param("sourceId", claim.sourceId())
			.param("questionId", claim.questionId())
			.param("answerId", claim.answerId())
			.param("token", claim.ingestionToken())
			.param("leaseUntil", claim.leaseUntil())
			.param("content", claim.document().chunkText())
			.param("embedding", vector)
			.param("actor", ACTOR)
			.query(Long.class)
			.optional();
		return publishedSource.isPresent()
			? AcceptedAnswerKnowledgeFinalizeResult.READY
			: AcceptedAnswerKnowledgeFinalizeResult.STALE;
	}

	private OffsetDateTime databaseNow() {
		return jdbc.sql("SELECT clock_timestamp()")
			.query(OffsetDateTime.class)
			.single();
	}

	private String vectorLiteral(List<Float> embedding) {
		Objects.requireNonNull(embedding, "embedding must not be null");
		if (embedding.size() != EMBEDDING_DIMENSIONS) {
			throw new IllegalArgumentException("embedding must contain exactly 768 values");
		}
		StringJoiner literal = new StringJoiner(",", "[", "]");
		for (Float value : embedding) {
			if (value == null || !Float.isFinite(value)) {
				throw new IllegalArgumentException("embedding values must be finite");
			}
			literal.add(Float.toString(value));
		}
		return literal.toString();
	}

	private void validateAnswerId(long answerId) {
		if (answerId <= 0) {
			throw new IllegalArgumentException("answerId must be positive");
		}
	}

	private void validatePositiveDuration(Duration duration, String name) {
		if (duration == null || duration.isZero() || duration.isNegative()) {
			throw new IllegalArgumentException(name + " must be positive");
		}
	}

	private void validateMaxAttempts(int maxAttempts) {
		if (maxAttempts <= 0 || maxAttempts > 5) {
			throw new IllegalArgumentException("maxAttempts must be between 1 and 5");
		}
	}

	private String sha256(String value) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
				.digest(value.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 must be available", exception);
		}
	}

	private record EligibleSnapshot(
		long questionId,
		long answerId,
		AcceptedAnswerKnowledgeSnapshot snapshot
	) {
	}

	private record StoredSource(
		long sourceId,
		String status,
		int attempts,
		OffsetDateTime nextAttemptAt,
		OffsetDateTime leaseUntil
	) {
	}
}
