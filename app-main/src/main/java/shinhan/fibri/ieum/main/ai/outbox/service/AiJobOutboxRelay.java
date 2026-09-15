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
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
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
 *
 * <p><b>lease 갱신(renewal)은 의도적으로 구현하지 않는다.</b> 이 relay 는 단일 인스턴스 배포를
 * 전제로 한다 — 여러 인스턴스가 늘어도 {@code FOR UPDATE SKIP LOCKED} + lease 로 이미 동시 클레임은
 * 안전하지만(spec.md §7.2), 배치 처리 도중 lease 를 연장해 만료를 미루는 로직은 없다. 처리 중
 * lease 가 만료되면 {@code recoverExpiredLeases}가 그 row 를 {@code retry}로 되돌리고, 원래
 * 처리자의 뒤늦은 정산(UPDATE)은 {@code markPublished}/{@code markRetry}/{@code markDead}의
 * {@code leaseToken} WHERE 절(펜싱)에 막혀 0행으로 끝난다({@code logStale} 참고) — 그 결과가
 * 중복 발행일지언정 유실은 아니다(spec.md §7.4의 at-least-once). {@link AiJobOutboxProperties}의
 * lease invariant(CodeRabbit PR #257 finding 4)가 이 배치 처리 시간이 lease 를 넘지 않도록
 * 정적으로 보장하는 이유가 여기 있다 — 갱신이 없으니 애초에 lease 를 넘기지 않게 설정값을
 * 강제해야 한다.
 */
@Service
@ConditionalOnProperty(name = "app.ai.dispatch.transport", havingValue = "rabbitmq")
public class AiJobOutboxRelay {

	/** AMQP {@code app_id}. 어느 앱이 발행했는지 브로커에서 바로 보이게 한다(spec.md §6.5). */
	public static final String APP_ID = "ieum-app-main";

	/** backoff 상한 (spec.md §7.5). */
	static final long MAX_BACKOFF_SECONDS = 60L;

	private static final Logger log = LoggerFactory.getLogger(AiJobOutboxRelay.class);

	/** {@code last_error_message}에 브로커 원문을 그대로 넣지 않는다 — 길이·내용 모두 통제 밖이다. */
	private static final String SAFE_ERROR_MESSAGE = "AI job publish failed";

	/**
	 * relay 전용 단일 스레드 스케줄러 빈 이름(CodeRabbit PR #257 finding 1). {@code pollAndPublish}는
	 * 배치당 최대 {@code batchSize}개 job 을 직렬로 발행하며 각 confirm 대기가 최대
	 * {@code confirmTimeout}(기본 5초)까지 걸릴 수 있다. app-main 의 공유 기본
	 * {@code TaskScheduler}({@link shinhan.fibri.ieum.config.SchedulingConfig}, 풀 크기 1)를 그대로
	 * 쓰면 이 배치 하나가 {@code SseHeartbeatScheduler}, {@code ContentPurgeScheduler},
	 * {@code ReportAiDispatchScheduler} 등 같은 풀을 쓰는 다른 모든 {@code @Scheduled} 작업을 몇
	 * 분씩 밀어낼 수 있다. 이 클래스의 세 {@code @Scheduled} 메서드를 전부 전용 스레드로 옮겨 다른
	 * 스케줄과 서로 영향을 주지 않게 한다.
	 */
	public static final String OUTBOX_SCHEDULER_BEAN_NAME = "aiJobOutboxScheduler";

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

	/** {@link #OUTBOX_SCHEDULER_BEAN_NAME} 참고 — app-main 공유 기본 스케줄러를 쓰지 않는다. */
	@Bean(OUTBOX_SCHEDULER_BEAN_NAME)
	TaskScheduler aiJobOutboxScheduler() {
		ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
		scheduler.setPoolSize(1);
		scheduler.setThreadNamePrefix("ai-job-outbox-");
		return scheduler;
	}

	@Scheduled(
		scheduler = OUTBOX_SCHEDULER_BEAN_NAME,
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
			properties.workerId(), leaseToken, properties.lease().toSeconds(), properties.batchSize(),
			properties.maxAttempts()
		);
		if (claimed.isEmpty()) {
			return 0;
		}
		log.debug(
			"event=ai_job_outbox_claimed workerId={} leaseToken={} claimedCount={}",
			properties.workerId(), leaseToken, claimed.size()
		);
		for (ClaimedAiJob job : claimed) {
			try {
				publishAndSettle(job, leaseToken);
			}
			catch (RuntimeException failure) {
				// job 하나의 예상 못한 실패(예: 정산 UPDATE 의 DataAccessException)가 나머지
				// 클레임된 job 을 건드리지 못하게 격리한다(PR #255 finding 4). 격리하지 않으면
				// 이 job 이후의 row 들이 lease 만 잡힌 채 publishing 으로 남아 lease 복구가
				// 회수할 때까지 방치된다. payload·에러 원문은 로그에 남기지 않는다.
				log.error(
					"event=ai_job_outbox_settle_failure workerId={} outboxId={} jobId={} failureType={}",
					properties.workerId(), job.outboxId(), job.jobId(), failure.getClass().getSimpleName()
				);
			}
		}
		return claimed.size();
	}

	@Scheduled(
		scheduler = OUTBOX_SCHEDULER_BEAN_NAME,
		fixedDelayString = "${app.ai.outbox.recovery-interval-ms:60000}",
		initialDelayString = "${app.ai.outbox.recovery-initial-delay-ms:60000}"
	)
	public void recoverExpiredLeases() {
		try {
			int recovered = repository.recoverExpiredLeases(properties.maxAttempts());
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
		scheduler = OUTBOX_SCHEDULER_BEAN_NAME,
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
		String failureCode = publishAndAwaitConfirm(job, exchange);
		// 정산은 브로커 왕복 try 밖에서 한다(PR #255 finding 3). markPublished/markRetry/markDead 가
		// DataAccessException 을 던지면, 그게 이 메서드를 호출한 publishBatch 로 그대로 전파돼야
		// confirm 결과와 무관한 DB 오류를 "발행 실패"로 오정산해 이미 성공한 발행을 재시도(중복 발행)
		// 시키는 사고를 막는다.
		if (failureCode == null) {
			markPublished(job, leaseToken, startedAt);
		}
		else {
			settleFailure(job, leaseToken, failureCode, startedAt);
		}
	}

	/** 브로커 왕복 + confirm 대기만 담당한다. 실패 원인 코드를 돌려줄 뿐 정산은 하지 않는다. */
	private String publishAndAwaitConfirm(ClaimedAiJob job, String exchange) {
		try {
			CorrelationData correlation = new CorrelationData(job.jobId().toString());
			rabbitTemplate.send(exchange, job.routingKey(), toMessage(job), correlation);

			CorrelationData.Confirm confirm = correlation.getFuture()
				.get(properties.confirmTimeout().toMillis(), TimeUnit.MILLISECONDS);
			ReturnedMessage returned = correlation.getReturned();
			if (returned != null) {
				// mandatory 반송. 큐가 아직 선언되지 않았거나 바인딩이 없다 — 브로커는 ACK 를 준다.
				return "returned";
			}
			if (confirm == null || !confirm.ack()) {
				return "nack";
			}
			return null;
		}
		catch (TimeoutException timeout) {
			// 발행 자체는 브로커에 닿았을 수 있다 → 재시도 시 중복 발행 가능. 소비 측 멱등성이 흡수한다.
			return "confirm_timeout";
		}
		catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			return "interrupted";
		}
		catch (ExecutionException | RuntimeException failure) {
			return "publish_failed";
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
	 * {@code send}로 바이트를 그대로 싣는다. outbox row 가 곧 wire 메시지라는 계약이다.
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
