package shinhan.fibri.ieum.main.admin.content.service;

import java.time.Clock;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import shinhan.fibri.ieum.main.admin.content.repository.AdminContentFileCleanupTask;
import shinhan.fibri.ieum.main.admin.content.repository.AdminContentFileCleanupTaskRepository;
import shinhan.fibri.ieum.main.file.service.S3FileDeletionService;

@Service
public class FileCleanupTaskProcessor {

	private static final Logger log = LoggerFactory.getLogger(FileCleanupTaskProcessor.class);

	private final AdminContentFileCleanupTaskRepository repository;
	private final S3FileDeletionService s3FileDeletionService;
	private final FileCleanupTaskProperties properties;
	private final Clock clock;

	public FileCleanupTaskProcessor(
		AdminContentFileCleanupTaskRepository repository,
		S3FileDeletionService s3FileDeletionService,
		FileCleanupTaskProperties properties
	) {
		this.repository = repository;
		this.s3FileDeletionService = s3FileDeletionService;
		this.properties = properties;
		this.clock = Clock.systemUTC();
	}

	public boolean processNext() {
		AdminContentFileCleanupTask claimed = repository.claimNext(
			properties.workerId(),
			properties.lease(),
			properties.maxAttempts()
		).orElse(null);
		if (claimed == null) {
			return false;
		}
		long startedAt = System.nanoTime();
		log.info(
			"event=file_cleanup_claimed taskId={} workerId={} attempts={} leaseUntil={}",
			claimed.taskId(),
			properties.workerId(),
			claimed.attempts(),
			claimed.leaseUntil()
		);
		try {
			s3FileDeletionService.deleteOriginAndVariantsLogOnly(claimed.s3Key());
			boolean transitioned = repository.markCompleted(claimed.taskId(), claimed.leaseToken());
			if (!transitioned) {
				log.warn(
					"event=file_cleanup_stale_discarded taskId={} workerId={} attempts={}",
					claimed.taskId(),
					properties.workerId(),
					claimed.attempts()
				);
				return true;
			}
			log.info(
				"event=file_cleanup_completed taskId={} workerId={} attempts={} durationMs={}",
				claimed.taskId(),
				properties.workerId(),
				claimed.attempts(),
				elapsedMs(startedAt)
			);
			return true;
		} catch (RuntimeException failure) {
			return handleFailure(claimed, failure, startedAt);
		}
	}

	private boolean handleFailure(AdminContentFileCleanupTask claimed, RuntimeException failure, long startedAt) {
		var disposition = FileCleanupTaskRetryPolicy.classify(failure, claimed.attempts(), properties.maxAttempts());
		if (disposition.retryable()) {
			OffsetDateTime nextAttemptAt = OffsetDateTime.ofInstant(
				clock.instant().plus(disposition.retryDelay()),
				clock.getZone()
			);
			boolean transitioned = repository.markRetry(
				claimed.taskId(),
				claimed.leaseToken(),
				nextAttemptAt,
				disposition.errorCode(),
				safeMessage(failure)
			);
			if (!transitioned) {
				log.warn(
					"event=file_cleanup_stale_discarded taskId={} workerId={} attempts={} errorCode={} durationMs={}",
					claimed.taskId(),
					properties.workerId(),
					claimed.attempts(),
					disposition.errorCode(),
					elapsedMs(startedAt)
				);
				return true;
			}
			log.warn(
				"event=file_cleanup_retry_scheduled taskId={} workerId={} attempts={} errorCode={} nextAttemptAt={} durationMs={}",
				claimed.taskId(),
				properties.workerId(),
				claimed.attempts(),
				disposition.errorCode(),
				nextAttemptAt,
				elapsedMs(startedAt)
			);
			return true;
		}

		boolean transitioned = repository.markDead(
			claimed.taskId(),
			claimed.leaseToken(),
			disposition.errorCode(),
			safeMessage(failure)
		);
		if (!transitioned) {
			log.warn(
				"event=file_cleanup_stale_discarded taskId={} workerId={} attempts={} errorCode={} durationMs={}",
				claimed.taskId(),
				properties.workerId(),
				claimed.attempts(),
				disposition.errorCode(),
				elapsedMs(startedAt)
			);
			return true;
		}
		log.error(
			"event=file_cleanup_dead taskId={} workerId={} attempts={} errorCode={} errorMessage={} durationMs={}",
			claimed.taskId(),
			properties.workerId(),
			claimed.attempts(),
			disposition.errorCode(),
			safeMessage(failure),
			elapsedMs(startedAt)
		);
		return true;
	}

	private static String safeMessage(RuntimeException failure) {
		if (failure == null) {
			return "UNKNOWN";
		}
		String message = failure.getClass().getSimpleName();
		if (failure.getMessage() == null || failure.getMessage().isBlank()) {
			return message;
		}
		String combined = message + ": " + failure.getMessage();
		return combined.length() > 500 ? combined.substring(0, 500) : combined;
	}

	private static long elapsedMs(long startedAt) {
		return Math.max(0, (System.nanoTime() - startedAt) / 1_000_000L);
	}
}
