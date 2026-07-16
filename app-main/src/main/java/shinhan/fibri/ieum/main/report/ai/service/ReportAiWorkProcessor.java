package shinhan.fibri.ieum.main.report.ai.service;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import shinhan.fibri.ieum.common.ai.report.dto.ReportReviewRequest;
import shinhan.fibri.ieum.common.ai.report.dto.ReportReviewResponse;
import shinhan.fibri.ieum.main.ai.client.AiServiceClient;
import shinhan.fibri.ieum.main.report.repository.ClaimedReport;
import shinhan.fibri.ieum.main.report.repository.ReportAiWorkRepository;

@Service
@ConditionalOnProperty(prefix = "app.ai.report", name = "enabled", havingValue = "true")
public class ReportAiWorkProcessor {

	private static final Logger log = LoggerFactory.getLogger(ReportAiWorkProcessor.class);
	private static final String SAFE_ERROR_MESSAGE = "Report AI processing failed";

	private final ReportAiWorkRepository repository;
	private final ReportReviewRequestFactory requestFactory;
	private final AiServiceClient aiServiceClient;
	private final ReportAiResultApplier resultApplier;
	private final ReportAiRetryPolicy retryPolicy;
	private final ReportAiWorkerProperties properties;
	private final Clock clock;

	public ReportAiWorkProcessor(
		ReportAiWorkRepository repository,
		ReportReviewRequestFactory requestFactory,
		AiServiceClient aiServiceClient,
		ReportAiResultApplier resultApplier,
		ReportAiRetryPolicy retryPolicy,
		ReportAiWorkerProperties properties,
		Clock clock
	) {
		this.repository = Objects.requireNonNull(repository, "repository must not be null");
		this.requestFactory = Objects.requireNonNull(requestFactory, "requestFactory must not be null");
		this.aiServiceClient = Objects.requireNonNull(aiServiceClient, "aiServiceClient must not be null");
		this.resultApplier = Objects.requireNonNull(resultApplier, "resultApplier must not be null");
		this.retryPolicy = Objects.requireNonNull(retryPolicy, "retryPolicy must not be null");
		this.properties = Objects.requireNonNull(properties, "properties must not be null");
		this.clock = Objects.requireNonNull(clock, "clock must not be null");
	}

	public boolean processNext() {
		ClaimedReport claimed = repository.claimNext(
			properties.workerId(), properties.lease(), properties.maxAttempts()
		).orElse(null);
		if (claimed == null) {
			return false;
		}
		long startedAt = System.nanoTime();
		log.info(
			"event=report_ai_claimed reportId={} attemptId={} workerId={} attempts={}",
			claimed.reportId(), claimed.attemptId(), properties.workerId(), claimed.attempts()
		);

		try {
			ReportReviewRequest request = requestFactory.create(claimed);
			ReportReviewResponse response = requestAiReview(claimed, request);
			ReportAiApplyOutcome outcome = resultApplier.apply(claimed, response);
			if (!outcome.transitioned()) {
				log.warn(
					"event=report_ai_stale_discarded reportId={} attemptId={} workerId={} attempts={} durationMs={}",
					claimed.reportId(), claimed.attemptId(), properties.workerId(), claimed.attempts(), elapsedMs(startedAt)
				);
				return true;
			}
			log.info(
				"event=report_ai_completed reportId={} attemptId={} workerId={} attempts={} decision={} sanctioned={} durationMs={}",
				claimed.reportId(), claimed.attemptId(), properties.workerId(), claimed.attempts(),
				outcome.decision(), outcome.sanctioned(), elapsedMs(startedAt)
			);
		} catch (RuntimeException failure) {
			handleFailure(claimed, failure, startedAt);
		}
		return true;
	}

	private ReportReviewResponse requestAiReview(ClaimedReport claimed, ReportReviewRequest request) {
		long startedAt = System.nanoTime();
		log.info(
			"event=report_ai_call_started reportId={} attemptId={} workerId={} attempts={}",
			claimed.reportId(), claimed.attemptId(), properties.workerId(), claimed.attempts()
		);
		try {
			ReportReviewResponse response = aiServiceClient.review(request);
			log.info(
				"event=report_ai_call_succeeded reportId={} attemptId={} workerId={} attempts={} durationMs={}",
				claimed.reportId(), claimed.attemptId(), properties.workerId(), claimed.attempts(), elapsedMs(startedAt)
			);
			return response;
		} catch (RuntimeException failure) {
			log.warn(
				"event=report_ai_call_failed reportId={} attemptId={} workerId={} attempts={} failureType={} durationMs={}",
				claimed.reportId(), claimed.attemptId(), properties.workerId(), claimed.attempts(),
				failure.getClass().getSimpleName(), elapsedMs(startedAt)
			);
			throw failure;
		}
	}

	private void handleFailure(ClaimedReport claimed, RuntimeException failure, long startedAt) {
		ReportAiFailureDisposition disposition = retryPolicy.classify(
			failure, claimed.attempts(), properties.maxAttempts()
		);
		if (disposition.retryable()) {
			OffsetDateTime nextAttemptAt = OffsetDateTime.ofInstant(
				clock.instant().plus(disposition.retryDelay()), clock.getZone()
			);
			boolean transitioned = repository.markRetry(
				claimed.reportId(), claimed.attemptId(), nextAttemptAt,
				disposition.errorCode(), SAFE_ERROR_MESSAGE
			);
			if (!transitioned) {
				logStaleFailure(claimed, disposition.errorCode(), startedAt);
				return;
			}
			log.warn(
				"event=report_ai_retry_scheduled reportId={} attemptId={} workerId={} attempts={} errorCode={} nextAttemptAt={} failureType={} durationMs={}",
				claimed.reportId(), claimed.attemptId(), properties.workerId(), claimed.attempts(),
				disposition.errorCode(), nextAttemptAt, failure.getClass().getSimpleName(), elapsedMs(startedAt)
			);
			return;
		}

		boolean transitioned = repository.markDead(
			claimed.reportId(), claimed.attemptId(), disposition.errorCode(), SAFE_ERROR_MESSAGE
		);
		if (!transitioned) {
			logStaleFailure(claimed, disposition.errorCode(), startedAt);
			return;
		}
		log.error(
			"event=report_ai_dead reportId={} attemptId={} workerId={} attempts={} errorCode={} failureType={} durationMs={}",
			claimed.reportId(), claimed.attemptId(), properties.workerId(), claimed.attempts(),
			disposition.errorCode(), failure.getClass().getSimpleName(), elapsedMs(startedAt)
		);
	}

	private void logStaleFailure(ClaimedReport claimed, String errorCode, long startedAt) {
		log.warn(
			"event=report_ai_stale_discarded reportId={} attemptId={} workerId={} attempts={} errorCode={} durationMs={}",
			claimed.reportId(), claimed.attemptId(), properties.workerId(), claimed.attempts(),
			errorCode, elapsedMs(startedAt)
		);
	}

	private long elapsedMs(long startedAt) {
		return Math.max(0, (System.nanoTime() - startedAt) / 1_000_000L);
	}
}
