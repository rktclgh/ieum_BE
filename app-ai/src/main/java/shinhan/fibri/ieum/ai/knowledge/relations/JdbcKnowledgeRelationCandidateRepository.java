package shinhan.fibri.ieum.ai.knowledge.relations;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import shinhan.fibri.ieum.ai.knowledge.accepted.AcceptedAnswerKnowledgeDocument;
import shinhan.fibri.ieum.ai.question.analysis.GeoScope;
import shinhan.fibri.ieum.ai.question.analysis.RegionContext;

public final class JdbcKnowledgeRelationCandidateRepository implements KnowledgeRelationCandidateRepository {

	private static final String ACTOR = "knowledge-relation-extraction";
	private static final String PROVIDER_FAILURE_CODE = "relation_extraction_provider_failed";
	private static final String INVALID_OUTPUT_CODE = "invalid_extraction_output";

	private final JdbcClient jdbc;
	private final TransactionTemplate transaction;

	public JdbcKnowledgeRelationCandidateRepository(
		JdbcClient jdbc,
		PlatformTransactionManager transactionManager
	) {
		this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
		this.transaction = new TransactionTemplate(
			Objects.requireNonNull(transactionManager, "transactionManager must not be null")
		);
		this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		this.transaction.setName("knowledge-relation-candidates");
	}

	@Override
	public void enqueue(long sourceId) {
		validateSourceId(sourceId);
		transaction.executeWithoutResult(status -> jdbc.sql("""
			INSERT INTO knowledge_relation_extraction_tasks(source_id, status, created_by, updated_by)
			SELECT ks.source_id, 'pending', :actor, :actor
			FROM knowledge_sources ks
			WHERE ks.source_id = :sourceId
			  AND ks.source_type = 'accepted_human_answer'
			  AND ks.status = 'ready'
			ON CONFLICT (source_id) DO UPDATE
			SET status = CASE
			        WHEN knowledge_relation_extraction_tasks.status IN ('completed', 'processing')
			            THEN knowledge_relation_extraction_tasks.status
			        ELSE 'pending'
			    END,
			    lease_token = CASE
			        WHEN knowledge_relation_extraction_tasks.status = 'processing'
			            THEN knowledge_relation_extraction_tasks.lease_token
			        ELSE NULL
			    END,
			    lease_until = CASE
			        WHEN knowledge_relation_extraction_tasks.status = 'processing'
			            THEN knowledge_relation_extraction_tasks.lease_until
			        ELSE NULL
			    END,
			    next_attempt_at = CASE
			        WHEN knowledge_relation_extraction_tasks.status = 'processing'
			            THEN knowledge_relation_extraction_tasks.next_attempt_at
			        ELSE NULL
			    END,
			    updated_by = :actor
			""")
			.param("sourceId", sourceId)
			.param("actor", ACTOR)
			.update());
	}

	@Override
	public Optional<ClaimedKnowledgeRelationExtractionTask> claimNext(Duration lease, int maxAttempts) {
		validatePositiveDuration(lease, "lease");
		validateMaxAttempts(maxAttempts);
		return Objects.requireNonNull(
			transaction.execute(status -> claimNextInTransaction(lease, maxAttempts)),
			"claim transaction returned null"
		);
	}

	@Override
	public void completeWithCandidates(
		ClaimedKnowledgeRelationExtractionTask task,
		List<KnowledgeRelationCandidate> candidates,
		String provider,
		String model
	) {
		Objects.requireNonNull(task, "task must not be null");
		List<KnowledgeRelationCandidate> safeCandidates =
			List.copyOf(Objects.requireNonNull(candidates, "candidates must not be null"));
		String safeProvider = required(provider, "provider");
		String safeModel = required(model, "model");
		transaction.executeWithoutResult(status -> {
			if (!lockClaimedTask(task)) {
				return;
			}
			for (KnowledgeRelationCandidate candidate : safeCandidates) {
				insertCandidate(task, candidate, safeProvider, safeModel);
			}
			completeTask(task, null, null);
		});
	}

	@Override
	public void completeInvalid(ClaimedKnowledgeRelationExtractionTask task, String message) {
		Objects.requireNonNull(task, "task must not be null");
		transaction.executeWithoutResult(status -> {
			if (lockClaimedTask(task)) {
				completeTask(task, INVALID_OUTPUT_CODE, message);
			}
		});
	}

	@Override
	public void markProviderFailure(
		ClaimedKnowledgeRelationExtractionTask task,
		Duration retryDelay,
		int maxAttempts,
		String message
	) {
		Objects.requireNonNull(task, "task must not be null");
		validatePositiveDuration(retryDelay, "retryDelay");
		validateMaxAttempts(maxAttempts);
		transaction.executeWithoutResult(status -> {
			if (!lockClaimedTask(task)) {
				return;
			}
			jdbc.sql("""
				UPDATE knowledge_relation_extraction_tasks
				SET status = CASE WHEN attempts >= :maxAttempts THEN 'dead' ELSE 'retry' END,
				    lease_token = NULL,
				    lease_until = NULL,
				    next_attempt_at = CASE
				        WHEN attempts >= :maxAttempts THEN NULL
				        ELSE clock_timestamp() + CAST(:retryMillis || ' milliseconds' AS interval)
				    END,
				    last_error_code = :errorCode,
				    last_error_message = :message,
				    updated_by = :actor
				WHERE task_id = :taskId
				""")
				.param("maxAttempts", maxAttempts)
				.param("retryMillis", retryDelay.toMillis())
				.param("errorCode", PROVIDER_FAILURE_CODE)
				.param("message", limit(message))
				.param("actor", ACTOR)
				.param("taskId", task.taskId())
				.update();
		});
	}

	private Optional<ClaimedKnowledgeRelationExtractionTask> claimNextInTransaction(
		Duration lease,
		int maxAttempts
	) {
		UUID token = UUID.randomUUID();
		return jdbc.sql("""
			WITH claimed AS (
			    SELECT task_id
			    FROM knowledge_relation_extraction_tasks
			    WHERE attempts < :maxAttempts
			      AND (
			          (status IN ('pending', 'retry')
			              AND (next_attempt_at IS NULL OR next_attempt_at <= clock_timestamp()))
			          OR
			          (status = 'processing' AND lease_until <= clock_timestamp())
			      )
			    ORDER BY next_attempt_at NULLS FIRST, created_at, task_id
			    FOR UPDATE SKIP LOCKED
			    LIMIT 1
			),
			updated AS (
			    UPDATE knowledge_relation_extraction_tasks task
			    SET status = 'processing',
			        lease_token = :token,
			        lease_until = clock_timestamp() + CAST(:leaseMillis || ' milliseconds' AS interval),
			        attempts = attempts + 1,
			        next_attempt_at = NULL,
			        last_error_code = NULL,
			        last_error_message = NULL,
			        updated_by = :actor
			    FROM claimed
			    WHERE task.task_id = claimed.task_id
			    RETURNING task.task_id, task.source_id, task.lease_token, task.lease_until, task.attempts
			)
			SELECT updated.task_id, updated.source_id, updated.lease_token, updated.lease_until,
			       updated.attempts, kc.chunk_id, ks.display_name, ks.content_hash, kc.content
			FROM updated
			JOIN knowledge_sources ks ON ks.source_id = updated.source_id
			JOIN knowledge_chunks kc ON kc.source_id = ks.source_id AND kc.chunk_order = 0
			WHERE ks.status = 'ready'
			""")
			.param("maxAttempts", maxAttempts)
			.param("token", token)
			.param("leaseMillis", lease.toMillis())
			.param("actor", ACTOR)
			.query((rs, row) -> new ClaimedKnowledgeRelationExtractionTask(
				rs.getLong("task_id"),
				rs.getLong("source_id"),
				rs.getLong("chunk_id"),
				UUID.fromString(rs.getString("lease_token")),
				rs.getObject("lease_until", OffsetDateTime.class),
				rs.getInt("attempts"),
				new AcceptedAnswerKnowledgeDocument(
					rs.getString("display_name"),
					rs.getString("content_hash").trim(),
					rs.getString("content"),
					GeoScope.general,
					RegionContext.empty(),
					0.0d,
					0.0d
				)
			))
			.optional();
	}

	private boolean lockClaimedTask(ClaimedKnowledgeRelationExtractionTask task) {
		return jdbc.sql("""
			SELECT task_id
			FROM knowledge_relation_extraction_tasks
			WHERE task_id = :taskId
			  AND source_id = :sourceId
			  AND status = 'processing'
			  AND lease_token = :token
			  AND lease_until = :leaseUntil
			  AND lease_until > clock_timestamp()
			FOR UPDATE
			""")
			.param("taskId", task.taskId())
			.param("sourceId", task.sourceId())
			.param("token", task.leaseToken())
			.param("leaseUntil", task.leaseUntil())
			.query(Long.class)
			.optional()
			.isPresent();
	}

	private void insertCandidate(
		ClaimedKnowledgeRelationExtractionTask task,
		KnowledgeRelationCandidate candidate,
		String provider,
		String model
	) {
		String fingerprint = fingerprint(task.sourceId(), candidate);
		jdbc.sql("""
			INSERT INTO knowledge_relation_candidates(
			    source_id, evidence_chunk_id, candidate_fingerprint,
			    subject_text, predicate, object_text, confidence, evidence_excerpt,
			    extraction_provider, extraction_model, status, created_by, updated_by
			)
			VALUES (
			    :sourceId, :chunkId, :fingerprint,
			    :subject, :predicate, :object, :confidence, :evidence,
			    :provider, :model, 'pending', :actor, :actor
			)
			ON CONFLICT (source_id, candidate_fingerprint) DO UPDATE
			SET confidence = EXCLUDED.confidence,
			    evidence_excerpt = EXCLUDED.evidence_excerpt,
			    extraction_provider = EXCLUDED.extraction_provider,
			    extraction_model = EXCLUDED.extraction_model,
			    updated_by = :actor
			""")
			.param("sourceId", task.sourceId())
			.param("chunkId", task.chunkId())
			.param("fingerprint", fingerprint)
			.param("subject", candidate.subject())
			.param("predicate", candidate.predicate().name())
			.param("object", candidate.object())
			.param("confidence", candidate.confidence())
			.param("evidence", candidate.evidenceExcerpt())
			.param("provider", provider)
			.param("model", model)
			.param("actor", ACTOR)
			.update();
	}

	private void completeTask(ClaimedKnowledgeRelationExtractionTask task, String errorCode, String message) {
		jdbc.sql("""
			UPDATE knowledge_relation_extraction_tasks
			SET status = 'completed',
			    lease_token = NULL,
			    lease_until = NULL,
			    next_attempt_at = NULL,
			    completed_at = clock_timestamp(),
			    last_error_code = :errorCode,
			    last_error_message = :message,
			    updated_by = :actor
			WHERE task_id = :taskId
			""")
			.param("errorCode", errorCode)
			.param("message", limit(message))
			.param("actor", ACTOR)
			.param("taskId", task.taskId())
			.update();
	}

	private String fingerprint(long sourceId, KnowledgeRelationCandidate candidate) {
		return sha256(sourceId + "\n" + candidate.subject() + "\n" + candidate.predicate().name()
			+ "\n" + candidate.object());
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

	private String required(String value, String name) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(name + " must not be blank");
		}
		return value.trim();
	}

	private String limit(String value) {
		if (value == null) {
			return null;
		}
		String trimmed = value.trim();
		return trimmed.length() <= 500 ? trimmed : trimmed.substring(0, 500);
	}

	private void validateSourceId(long sourceId) {
		if (sourceId < 1) {
			throw new IllegalArgumentException("sourceId must be positive");
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
}
