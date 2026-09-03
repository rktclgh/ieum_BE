package shinhan.fibri.ieum.main.ai.outbox.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import shinhan.fibri.ieum.common.ai.job.AiJobTopology;
import shinhan.fibri.ieum.common.ai.job.AiJobType;
import shinhan.fibri.ieum.common.ai.job.dto.AcceptedAnswerKnowledgeMessage;
import shinhan.fibri.ieum.common.ai.job.dto.QuestionAnswerDispatchMessage;
import shinhan.fibri.ieum.main.ai.outbox.entity.AiJobOutbox;
import shinhan.fibri.ieum.main.ai.outbox.repository.AiJobOutboxRepository;

/**
 * {@code ai_job_outbox} row를 실제로 쓰는 구현. {@code app.ai.dispatch.transport=rabbitmq}일 때만
 * 활성화된다(application.properties 주석이 단일 진실 원천). spec.md §7.3/§8.1.
 *
 * <p>{@link Propagation#MANDATORY}로 강제한다 — 호출자가 이미 연 트랜잭션이 없으면 즉시 실패한다.
 * 이게 이 클래스의 핵심 안전장치다: 실수로 트랜잭션 밖에서 부르면(TX 커밋 후, 별도 스레드 등)
 * outbox row가 도메인 변경과 분리된 채로 쓰이는 사고를 컴파일이 아니라 런타임에서 막는다.
 *
 * <p>row는 {@code pending}으로 쌓이기만 한다 — 발행은 {@link AiJobOutboxRelay}가 한다.
 */
@Service
@ConditionalOnProperty(name = "app.ai.dispatch.transport", havingValue = "rabbitmq")
@RequiredArgsConstructor
public class JpaAiJobOutboxWriter implements AiJobOutboxWriter {

	private static final DateTimeFormatter OCCURRED_AT_FORMATTER = DateTimeFormatter
		.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
		.withZone(ZoneOffset.UTC);

	private final AiJobOutboxRepository repository;
	private final ObjectMapper objectMapper;

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public void enqueueQuestionAnswerDispatch(Long questionId, Reason reason) {
		if (questionId == null || questionId <= 0) {
			throw new IllegalArgumentException("questionId must be positive: " + questionId);
		}
		Objects.requireNonNull(reason, "reason must not be null");

		UUID jobId = UUID.randomUUID();
		QuestionAnswerDispatchMessage message = new QuestionAnswerDispatchMessage(
			AiJobTopology.SCHEMA_VERSION,
			jobId.toString(),
			AiJobType.QUESTION_ANSWER_DISPATCH,
			questionId,
			reason.value(),
			nowIso()
		);

		repository.save(AiJobOutbox.pending(
			jobId,
			AiJobType.QUESTION_ANSWER_DISPATCH.value(),
			questionId,
			AiJobTopology.ROUTING_KEY_QUESTION_ANSWER_DISPATCH,
			AiJobTopology.SCHEMA_VERSION,
			writeJson(message)
		));
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public void enqueueAcceptedAnswerKnowledge(Long answerId) {
		if (answerId == null || answerId <= 0) {
			throw new IllegalArgumentException("answerId must be positive: " + answerId);
		}

		UUID jobId = UUID.randomUUID();
		AcceptedAnswerKnowledgeMessage message = new AcceptedAnswerKnowledgeMessage(
			AiJobTopology.SCHEMA_VERSION,
			jobId.toString(),
			AiJobType.ACCEPTED_ANSWER_KNOWLEDGE_INGEST,
			answerId,
			nowIso()
		);

		repository.save(AiJobOutbox.pending(
			jobId,
			AiJobType.ACCEPTED_ANSWER_KNOWLEDGE_INGEST.value(),
			answerId,
			AiJobTopology.ROUTING_KEY_ACCEPTED_ANSWER_INGEST,
			AiJobTopology.SCHEMA_VERSION,
			writeJson(message)
		));
	}

	private String writeJson(Object message) {
		try {
			return objectMapper.writeValueAsString(message);
		}
		catch (JsonProcessingException exception) {
			throw new IllegalStateException("Failed to serialize AI job message: " + message, exception);
		}
	}

	/** ISO-8601 UTC, 밀리초 정밀도, {@code Z} 접미사. common 골든 픽스처 포맷과 일치시킨다. */
	private String nowIso() {
		return OCCURRED_AT_FORMATTER.format(Instant.now());
	}
}
