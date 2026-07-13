package shinhan.fibri.ieum.ai.question.checkpoint;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import shinhan.fibri.ieum.ai.question.analysis.QueryAnalysis;
import shinhan.fibri.ieum.ai.question.analysis.RegionContext;
import shinhan.fibri.ieum.ai.question.embedding.QuestionEmbedding;
import shinhan.fibri.ieum.ai.question.repository.ClaimedQuestionTask;

@Repository
public class JdbcQuestionCheckpointRepository implements QuestionCheckpointRepository {

	private final JdbcClient jdbc;
	private final ObjectMapper objectMapper;

	public JdbcQuestionCheckpointRepository(JdbcClient jdbc, ObjectMapper objectMapper) {
		this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
		this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
	}

	@Override
	public Optional<LockedQuestionCheckpoint> lockCurrentFence(ClaimedQuestionTask claim) {
		return jdbc.sql("""
			SELECT cancel_requested_at IS NOT NULL AS cancellation_requested
			FROM ai_question_tasks
			WHERE question_id = :questionId
			  AND status = 'processing'
			  AND locked_by = :workerId
			  AND lease_token = :leaseToken
			  AND lease_until > clock_timestamp()
			FOR UPDATE
			""")
			.param("questionId", claim.questionId())
			.param("workerId", claim.workerId())
			.param("leaseToken", claim.leaseToken())
			.query((resultSet, rowNumber) -> new LockedQuestionCheckpoint(
				resultSet.getBoolean("cancellation_requested")
			))
			.optional();
	}

	@Override
	public boolean lockActiveQuestion(long questionId) {
		return jdbc.sql("SELECT public.ai_lock_active_question(:questionId)")
			.param("questionId", questionId)
			.query(Boolean.class)
			.single();
	}

	@Override
	public boolean cancelCurrentFence(ClaimedQuestionTask claim) {
		return jdbc.sql("""
			UPDATE ai_question_tasks
			SET status = 'cancelled',
			    cancelled_at = CURRENT_TIMESTAMP,
			    lease_until = NULL,
			    locked_by = NULL,
			    lease_token = NULL,
			    updated_at = CURRENT_TIMESTAMP
			WHERE question_id = :questionId
			  AND status = 'processing'
			  AND locked_by = :workerId
			  AND lease_token = :leaseToken
			  AND lease_until > clock_timestamp()
			""")
			.param("questionId", claim.questionId())
			.param("workerId", claim.workerId())
			.param("leaseToken", claim.leaseToken())
			.update() == 1;
	}

	@Override
	public boolean saveAnalysis(
		ClaimedQuestionTask claim,
		QueryAnalysis analysis,
		Duration leaseExtension
	) {
		return jdbc.sql("""
			UPDATE ai_question_tasks
			SET geo_scope = :geoScope,
			    geo_scope_confidence = :geoScopeConfidence,
			    region_context = CAST(:regionContext AS jsonb),
			    analysis_version = :analysisVersion,
			    stage = 'embedding',
			    lease_until = clock_timestamp() + (:leaseSeconds * INTERVAL '1 second'),
			    updated_at = CURRENT_TIMESTAMP
			WHERE question_id = :questionId
			  AND status = 'processing'
			  AND stage = 'analyzing'
			  AND locked_by = :workerId
			  AND lease_token = :leaseToken
			  AND lease_until > clock_timestamp()
			""")
			.param("geoScope", analysis.geoScope().name())
			.param("geoScopeConfidence", analysis.confidence())
			.param("regionContext", json(regionContextJson(analysis.regionContext())))
			.param("analysisVersion", analysis.analysisVersion())
			.param("leaseSeconds", leaseExtension.toSeconds())
			.param("questionId", claim.questionId())
			.param("workerId", claim.workerId())
			.param("leaseToken", claim.leaseToken())
			.update() == 1;
	}

	@Override
	public boolean saveEmbedding(
		ClaimedQuestionTask claim,
		QuestionEmbedding embedding,
		Duration leaseExtension
	) {
		return jdbc.sql("""
			UPDATE ai_question_tasks
			SET embedding = CAST(:embedding AS vector),
			    embedding_model = :embeddingModel,
			    stage = 'retrieving',
			    lease_until = clock_timestamp() + (:leaseSeconds * INTERVAL '1 second'),
			    updated_at = CURRENT_TIMESTAMP
			WHERE question_id = :questionId
			  AND status = 'processing'
			  AND stage = 'embedding'
			  AND locked_by = :workerId
			  AND lease_token = :leaseToken
			  AND lease_until > clock_timestamp()
			""")
			.param("embedding", vectorLiteral(embedding.values()))
			.param("embeddingModel", embedding.model())
			.param("leaseSeconds", leaseExtension.toSeconds())
			.param("questionId", claim.questionId())
			.param("workerId", claim.workerId())
			.param("leaseToken", claim.leaseToken())
			.update() == 1;
	}

	@Override
	public boolean advanceStage(
		ClaimedQuestionTask claim,
		QuestionTaskStage expectedStage,
		QuestionTaskStage nextStage,
		Duration leaseExtension
	) {
		return jdbc.sql("""
			UPDATE ai_question_tasks
			SET stage = CAST(:nextStage AS ai_job_stage),
			    lease_until = clock_timestamp() + (:leaseSeconds * INTERVAL '1 second'),
			    updated_at = CURRENT_TIMESTAMP
			WHERE question_id = :questionId
			  AND status = 'processing'
			  AND stage = CAST(:expectedStage AS ai_job_stage)
			  AND locked_by = :workerId
			  AND lease_token = :leaseToken
			  AND lease_until > clock_timestamp()
			""")
			.param("nextStage", nextStage.databaseValue())
			.param("leaseSeconds", leaseExtension.toSeconds())
			.param("questionId", claim.questionId())
			.param("expectedStage", expectedStage.databaseValue())
			.param("workerId", claim.workerId())
			.param("leaseToken", claim.leaseToken())
			.update() == 1;
	}

	private ObjectNode regionContextJson(RegionContext region) {
		ObjectNode json = objectMapper.createObjectNode();
		putNullable(json, "country", region.country());
		putNullable(json, "sido", region.sido());
		putNullable(json, "sigungu", region.sigungu());
		putNullable(json, "eupMyeonDong", region.eupMyeonDong());
		putNullable(json, "place", region.place());
		return json;
	}

	private void putNullable(ObjectNode json, String field, String value) {
		if (value == null) {
			json.putNull(field);
			return;
		}
		json.put(field, value);
	}

	private String vectorLiteral(List<Float> embedding) {
		StringBuilder literal = new StringBuilder(embedding.size() * 8).append('[');
		for (int index = 0; index < embedding.size(); index++) {
			if (index > 0) {
				literal.append(',');
			}
			literal.append(Float.toString(embedding.get(index)));
		}
		return literal.append(']').toString();
	}

	private String json(JsonNode value) {
		try {
			return objectMapper.writeValueAsString(value);
		} catch (JsonProcessingException exception) {
			throw new IllegalArgumentException("Question checkpoint JSON cannot be serialized", exception);
		}
	}
}
