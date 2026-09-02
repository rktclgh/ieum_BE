package shinhan.fibri.ieum.main.ai.outbox.service;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import shinhan.fibri.ieum.common.ai.job.AiJobTopology;
import shinhan.fibri.ieum.main.ai.outbox.repository.AiJobOutboxRepository;
import shinhan.fibri.ieum.main.ai.outbox.repository.ClaimedAiJob;

/**
 * {@code ai_job_outbox} → RabbitMQ 릴레이. spec.md §7.
 *
 * <p><b>트랜잭션 경계가 이 클래스의 존재 이유다.</b> 이 클래스에는 {@code @Transactional}이 하나도 없다.
 * <pre>
 *   [TX 1 클레임]  repository.claim(...)        ← 짧은 쓰기 TX, 즉시 커밋
 *        ↓ (트랜잭션 밖, DB 커넥션·row lock 모두 반납한 상태)
 *   [브로커 왕복]  rabbitTemplate.send(...) + confirm 대기
 *        ↓
 *   [TX 2 정산]   markPublished / markRetry / markDead
 * </pre>
 * 브로커가 죽어 confirm 이 5초 걸리는 동안 DB 커넥션을 붙잡고 있으면 커넥션 풀이 말라
 * 애플리케이션 전체가 같이 죽는다. {@code ReportAiWorkProcessor}가 HTTP 호출에 대해 지키는 규칙과 같다.
 *
 * <p>의미론은 정확히 at-least-once 다(spec.md §7.4). 중복 발행은 소비 측 멱등성이 흡수한다.
 */
@Service
@ConditionalOnProperty(prefix = "app.ai.outbox", name = "enabled", havingValue = "true")
public class AiJobOutboxRelay {

	/** AMQP {@code app_id}. 어느 앱이 발행했는지 브로커에서 바로 보이게 한다(spec.md §6.5). */
	public static final String APP_ID = "ieum-app-main";

	/** backoff 상한 (spec.md §7.5). */
	static final long MAX_BACKOFF_SECONDS = 60L;

	private static final Logger log = LoggerFactory.getLogger(AiJobOutboxRelay.class);

	/** {@code last_error_message}에 브로커 원문을 그대로 넣지 않는다 — 길이·내용 모두 통제 밖이다. */
	private static final String SAFE_ERROR_MESSAGE = "AI job publish failed";

	private final AiJobOutboxRepository repository;
	private final RabbitTemplate rabbitTemplate;
	private final AiJobOutboxProperties properties;

	public AiJobOutboxRelay(
		AiJobOutboxRepository repository,
		RabbitTemplate rabbitTemplate,
		AiJobOutboxProperties properties
	) {
		this.repository = Objects.requireNonNull(repository, "repository must not be null");
		this.rabbitTemplate = Objects.requireNonNull(rabbitTemplate, "rabbitTemplate must not be null");
		this.properties = Objects.requireNonNull(properties, "properties must not be null");
	}

	/**
	 * {@code next_attempt_at = now() + min(2^(attempts-1), 60)초} (spec.md §7.5).
	 *
	 * <p>클레임이 {@code attempts}를 먼저 1 증가시키므로 첫 실패의 {@code attempts}는 1이고 대기는 1초다.
	 * 1, 2, 4, 8, 16, 32, 60, 60...
	 */
	public static long backoffSeconds(int attempts) {
		if (attempts < 1) {
			throw new IllegalArgumentException("attempts must be positive: " + attempts);
		}
		if (attempts >= 7) {
			return MAX_BACKOFF_SECONDS;
		}
		return Math.min(1L << (attempts - 1), MAX_BACKOFF_SECONDS);
	}

	@Scheduled(
		fixedDelayString = "${app.ai.outbox.poll-delay-ms:500}",
		initialDelayString = "${app.ai.outbox.poll-initial-delay-ms:5000}"
	)
	public void pollAndPublish() {
		try {
			publishBatch();
		}
		catch (RuntimeException failure) {
			log.error(
				"event=ai_job_outbox_poll_failure workerId={} failureType={}",
				properties.workerId(), failure.getClass().getSimpleName()
			);
		}
	}

	/** 한 배치를 클레임해 전부 발행·정산한다. 클레임한 건수를 돌려준다. */
	public int publishBatch() {
		UUID leaseToken = UUID.randomUUID();
		List<ClaimedAiJob> claimed = repository.claim(
			properties.workerId(), leaseToken, properties.lease().toSeconds(), properties.batchSize()
		);
		if (claimed.isEmpty()) {
			return 0;
		}
		log.debug(
			"event=ai_job_outbox_claimed workerId={} leaseToken={} claimedCount={}",
			properties.workerId(), leaseToken, claimed.size()
		);
		for (ClaimedAiJob job : claimed) {
			publishAndSettle(job, leaseToken);
		}
		return claimed.size();
	}

	@Scheduled(
		fixedDelayString = "${app.ai.outbox.recovery-interval-ms:60000}",
		initialDelayString = "${app.ai.outbox.recovery-initial-delay-ms:60000}"
	)
	public void recoverExpiredLeases() {
		try {
			int recovered = repository.recoverExpiredLeases();
			if (recovered > 0) {
				log.warn(
					"event=ai_job_outbox_expired_lease_recovered workerId={} recoveredCount={}",
					properties.workerId(), recovered
				);
			}
			long backlog = repository.countBacklog();
			if (backlog >= properties.backlogWarnThreshold()) {
				log.warn(
					"event=ai_job_outbox_backlog_high workerId={} backlog={} threshold={}",
					properties.workerId(), backlog, properties.backlogWarnThreshold()
				);
			}
		}
		catch (RuntimeException failure) {
			log.error(
				"event=ai_job_outbox_recovery_failure workerId={} failureType={}",
				properties.workerId(), failure.getClass().getSimpleName()
			);
		}
	}

	/** 보존 삭제. {@code published}만 배치로 지운다 — {@code dead}는 운영 증거로 남긴다(spec.md §7.5). */
	@Scheduled(
		fixedDelayString = "${app.ai.outbox.retention-interval-ms:3600000}",
		initialDelayString = "${app.ai.outbox.retention-initial-delay-ms:3600000}"
	)
	public void purgePublishedJobs() {
		try {
			int total = 0;
			int deleted;
			do {
				deleted = repository.deletePublishedOlderThanDays(
					properties.retentionDays(), AiJobOutboxRepository.RETENTION_DELETE_BATCH
				);
				total += deleted;
			}
			while (deleted == AiJobOutboxRepository.RETENTION_DELETE_BATCH);
			if (total > 0) {
				log.info(
					"event=ai_job_outbox_retention_purged workerId={} deletedCount={} retentionDays={}",
					properties.workerId(), total, properties.retentionDays()
				);
			}
		}
		catch (RuntimeException failure) {
			log.error(
				"event=ai_job_outbox_retention_failure workerId={} failureType={}",
				properties.workerId(), failure.getClass().getSimpleName()
			);
		}
	}

	private void publishAndSettle(ClaimedAiJob job, UUID leaseToken) {
		String exchange = exchangeFor(job.routingKey());
		if (exchange == null) {
			// 재시도해도 고쳐지지 않는다. 즉시 dead 로 보내 큐를 막지 않는다(spec.md §6.3의 poison 취급).
			markDead(job, leaseToken, "unknown_routing_key");
			return;
		}

		long startedAt = System.nanoTime();
		try {
			CorrelationData correlation = new CorrelationData(job.jobId().toString());
			rabbitTemplate.send(exchange, job.routingKey(), toMessage(job), correlation);

			CorrelationData.Confirm confirm = correlation.getFuture()
				.get(properties.confirmTimeout().toMillis(), TimeUnit.MILLISECONDS);
			ReturnedMessage returned = correlation.getReturned();
			if (returned != null) {
				// mandatory 반송. 큐가 아직 선언되지 않았거나 바인딩이 없다 — 브로커는 ACK 를 준다.
				settleFailure(job, leaseToken, "returned", startedAt);
				return;
			}
			if (confirm == null || !confirm.ack()) {
				settleFailure(job, leaseToken, "nack", startedAt);
				return;
			}
			markPublished(job, leaseToken, startedAt);
		}
		catch (TimeoutException timeout) {
			// 발행 자체는 브로커에 닿았을 수 있다 → 재시도 시 중복 발행 가능. 소비 측 멱등성이 흡수한다.
			settleFailure(job, leaseToken, "confirm_timeout", startedAt);
		}
		catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			settleFailure(job, leaseToken, "interrupted", startedAt);
		}
		catch (ExecutionException | RuntimeException failure) {
			settleFailure(job, leaseToken, "publish_failed", startedAt);
		}
	}

	private void markPublished(ClaimedAiJob job, UUID leaseToken, long startedAt) {
		if (repository.markPublished(job.outboxId(), leaseToken) == 0) {
			logStale(job, "published");
			return;
		}
		log.info(
			"event=ai_job_outbox_published workerId={} outboxId={} jobId={} jobType={} routingKey={} attempts={} durationMs={}",
			properties.workerId(), job.outboxId(), job.jobId(), job.jobType(), job.routingKey(),
			job.attempts(), elapsedMs(startedAt)
		);
	}

	private void settleFailure(ClaimedAiJob job, UUID leaseToken, String errorCode, long startedAt) {
		if (job.attempts() >= properties.maxAttempts()) {
			markDead(job, leaseToken, errorCode);
			return;
		}
		long backoff = backoffSeconds(job.attempts());
		if (repository.markRetry(job.outboxId(), leaseToken, backoff, errorCode, SAFE_ERROR_MESSAGE) == 0) {
			logStale(job, "retry");
			return;
		}
		log.warn(
			"event=ai_job_outbox_retry_scheduled workerId={} outboxId={} jobId={} routingKey={} attempts={} errorCode={} backoffSeconds={} durationMs={}",
			properties.workerId(), job.outboxId(), job.jobId(), job.routingKey(), job.attempts(),
			errorCode, backoff, elapsedMs(startedAt)
		);
	}

	private void markDead(ClaimedAiJob job, UUID leaseToken, String errorCode) {
		if (repository.markDead(job.outboxId(), leaseToken, errorCode, SAFE_ERROR_MESSAGE) == 0) {
			logStale(job, "dead");
			return;
		}
		log.error(
			"event=ai_job_outbox_dead workerId={} outboxId={} jobId={} jobType={} routingKey={} attempts={} maxAttempts={} errorCode={}",
			properties.workerId(), job.outboxId(), job.jobId(), job.jobType(), job.routingKey(),
			job.attempts(), properties.maxAttempts(), errorCode
		);
	}

	/**
	 * 정산 UPDATE 가 0행이면 lease 가 만료돼 다른 워커(또는 복구 스케줄)가 이미 row 를 가져간 것이다.
	 * 이쪽의 늦은 결과는 버린다 — 펜싱이 의도대로 동작한 정상 경로다.
	 */
	private void logStale(ClaimedAiJob job, String attemptedTransition) {
		log.warn(
			"event=ai_job_outbox_stale_settlement_discarded workerId={} outboxId={} jobId={} attemptedTransition={}",
			properties.workerId(), job.outboxId(), job.jobId(), attemptedTransition
		);
	}

	/**
	 * outbox 의 {@code payload}(jsonb)를 <b>그대로</b> 본문으로 싣는다.
	 *
	 * <p>{@code convertAndSend}로 보내면 {@code MessageConverter}가 이 JSON <b>문자열</b>을 다시
	 * 직렬화해 따옴표로 감싼 문자열 하나가 본문이 된다 — 소비 측이 파싱할 수 없다. 그래서
	 * {@code send}로 바이트를 그대로 싣는다. outbox row 가 곧 wire 메시지라는 Task 3 의 계약이다.
	 *
	 * <p>다만 컬럼이 {@code jsonb}라 저장 시점에 키 순서·공백은 Postgres 가 정규화한다. 즉 바이트가
	 * 아니라 <b>JSON 값</b>이 보존된다. 소비 측은 Jackson 으로 파싱하므로 문제되지 않고,
	 * common 의 골든 픽스처 비교도 필드 순서 무관으로 되어 있다.
	 */
	private Message toMessage(ClaimedAiJob job) {
		MessageProperties messageProperties = new MessageProperties();
		messageProperties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
		messageProperties.setContentEncoding(StandardCharsets.UTF_8.name());
		messageProperties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
		messageProperties.setMessageId(job.jobId().toString());
		messageProperties.setCorrelationId(job.jobId().toString());
		messageProperties.setType(job.jobType());
		messageProperties.setAppId(APP_ID);
		messageProperties.setHeader("x-ieum-schema-version", job.schemaVersion());
		return new Message(job.payload().getBytes(StandardCharsets.UTF_8), messageProperties);
	}

	/** app-main 이 발행할 수 있는 routing key 만 허용한다. 결과 큐({@code ai.question-answer.completed})는 app-ai 몫이다. */
	private static String exchangeFor(String routingKey) {
		if (AiJobTopology.ROUTING_KEY_QUESTION_ANSWER_DISPATCH.equals(routingKey)
			|| AiJobTopology.ROUTING_KEY_ACCEPTED_ANSWER_INGEST.equals(routingKey)) {
			return AiJobTopology.EXCHANGE_JOBS;
		}
		return null;
	}

	private long elapsedMs(long startedAt) {
		return Math.max(0, (System.nanoTime() - startedAt) / 1_000_000L);
	}
}
