package shinhan.fibri.ieum.ai.question.finalization;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Types;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcQuestionAnswerFinalizationRepository {

	private final JdbcClient jdbc;
	private final ObjectMapper objectMapper;

	public JdbcQuestionAnswerFinalizationRepository(JdbcClient jdbc, ObjectMapper objectMapper) {
		this.jdbc = jdbc;
		this.objectMapper = objectMapper;
	}

	boolean lockCurrentFence(QuestionTaskFence fence) {
		return jdbc.sql("""
			SELECT question_id
			FROM ai_question_tasks
			WHERE question_id = :questionId
			  AND status = 'processing'
			  AND stage = 'persisting'
			  AND locked_by = :workerId
			  AND lease_token = :leaseToken
			  AND lease_until > clock_timestamp()
			  AND cancel_requested_at IS NULL
			FOR UPDATE
			""")
			.param("questionId", fence.questionId())
			.param("workerId", fence.workerId())
			.param("leaseToken", fence.leaseToken())
			.query(Long.class)
			.optional()
			.isPresent();
	}

	boolean lockActiveQuestion(long questionId) {
		return jdbc.sql("SELECT public.ai_lock_active_question(:questionId)")
			.param("questionId", questionId)
			.query(Boolean.class)
			.single();
	}

	boolean cancelCurrentFence(QuestionTaskFence fence) {
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
			  AND stage = 'persisting'
			  AND locked_by = :workerId
			  AND lease_token = :leaseToken
			  AND lease_until > clock_timestamp()
			""")
			.param("questionId", fence.questionId())
			.param("workerId", fence.workerId())
			.param("leaseToken", fence.leaseToken())
			.update() == 1;
	}

	long insertAnswerIfActive(long questionId, String content) {
		return jdbc.sql("SELECT public.insert_ai_answer_if_active(:questionId, :content)")
			.param("questionId", questionId)
			.param("content", content)
			.query(Long.class)
			.single();
	}

	boolean completeGrounded(
		GroundedQuestionAnswerFinalization command,
		long answerId
	) {
		QuestionTaskFence fence = command.fence();
		QuestionAnswerFinalizationContext context = command.context();
		return jdbc.sql("""
			UPDATE ai_question_tasks
			SET status = 'completed',
			    stage = 'persisting',
			    embedding = CAST(:embedding AS vector),
			    embedding_model = :embeddingModel,
			    geo_scope = :geoScope,
			    geo_scope_confidence = :geoScopeConfidence,
			    region_context = CAST(:regionContext AS jsonb),
			    answer_id = :answerId,
			    answer_outcome = :answerOutcome,
			    generation_provider = :generationProvider,
			    generation_model = :generationModel,
			    retrieval_config_version = :retrievalConfigVersion,
			    fallback_reason = :fallbackReason,
			    prompt_version = :promptVersion,
			    grounding_status = 'grounded',
			    grounding_score = :groundingScore,
			    evidence = CAST(:evidence AS jsonb),
			    last_error_code = NULL,
			    last_error_message = NULL,
			    completed_at = CURRENT_TIMESTAMP,
			    lease_until = NULL,
			    locked_by = NULL,
			    lease_token = NULL,
			    updated_at = CURRENT_TIMESTAMP
			WHERE question_id = :questionId
			  AND status = 'processing'
			  AND stage = 'persisting'
			  AND locked_by = :workerId
			  AND lease_token = :leaseToken
			  AND lease_until > clock_timestamp()
			  AND cancel_requested_at IS NULL
			""")
			.param("embedding", vectorLiteral(context.embedding()))
			.param("embeddingModel", context.embeddingModel())
			.param("geoScope", context.geoScope().name())
			.param("geoScopeConfidence", context.geoScopeConfidence())
			.param("regionContext", json(context.regionContext()))
			.param("answerId", answerId)
			.param("answerOutcome", command.answerMode().databaseValue())
			.param("generationProvider", context.generationProvider(), Types.VARCHAR)
			.param("generationModel", context.generationModel(), Types.VARCHAR)
			.param("retrievalConfigVersion", context.retrievalConfigVersion())
			.param("fallbackReason", context.fallbackReason(), Types.VARCHAR)
			.param("promptVersion", context.promptVersion(), Types.VARCHAR)
			.param("groundingScore", context.groundingScore())
			.param("evidence", evidenceJson(context.evidence()))
			.param("questionId", fence.questionId())
			.param("workerId", fence.workerId())
			.param("leaseToken", fence.leaseToken())
			.update() == 1;
	}

	boolean completeInsufficient(InsufficientQuestionAnswerFinalization command) {
		QuestionTaskFence fence = command.fence();
		QuestionAnswerFinalizationContext context = command.context();
		return jdbc.sql("""
			UPDATE ai_question_tasks
			SET status = 'completed',
			    stage = 'persisting',
			    embedding = CAST(:embedding AS vector),
			    embedding_model = :embeddingModel,
			    geo_scope = :geoScope,
			    geo_scope_confidence = :geoScopeConfidence,
			    region_context = CAST(:regionContext AS jsonb),
			    answer_id = NULL,
			    answer_outcome = 'insufficient_evidence',
			    generation_provider = :generationProvider,
			    generation_model = :generationModel,
			    retrieval_config_version = :retrievalConfigVersion,
			    fallback_reason = :fallbackReason,
			    prompt_version = :promptVersion,
			    grounding_status = 'insufficient_evidence',
			    grounding_score = :groundingScore,
			    evidence = '[]'::jsonb,
			    last_error_code = NULL,
			    last_error_message = NULL,
			    completed_at = CURRENT_TIMESTAMP,
			    lease_until = NULL,
			    locked_by = NULL,
			    lease_token = NULL,
			    updated_at = CURRENT_TIMESTAMP
			WHERE question_id = :questionId
			  AND status = 'processing'
			  AND stage = 'persisting'
			  AND locked_by = :workerId
			  AND lease_token = :leaseToken
			  AND lease_until > clock_timestamp()
			  AND cancel_requested_at IS NULL
			""")
			.param("embedding", vectorLiteral(context.embedding()))
			.param("embeddingModel", context.embeddingModel())
			.param("geoScope", context.geoScope().name())
			.param("geoScopeConfidence", context.geoScopeConfidence())
			.param("regionContext", json(context.regionContext()))
			.param("generationProvider", context.generationProvider(), Types.VARCHAR)
			.param("generationModel", context.generationModel(), Types.VARCHAR)
			.param("retrievalConfigVersion", context.retrievalConfigVersion())
			.param("fallbackReason", context.fallbackReason(), Types.VARCHAR)
			.param("promptVersion", context.promptVersion(), Types.VARCHAR)
			.param("groundingScore", context.groundingScore())
			.param("questionId", fence.questionId())
			.param("workerId", fence.workerId())
			.param("leaseToken", fence.leaseToken())
			.update() == 1;
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

	private String evidenceJson(List<JsonNode> evidence) {
		return json(objectMapper.createArrayNode().addAll(evidence));
	}

	private String json(JsonNode value) {
		try {
			return objectMapper.writeValueAsString(value);
		} catch (JsonProcessingException exception) {
			throw new IllegalArgumentException("Finalization JSON cannot be serialized", exception);
		}
	}
}
