package shinhan.fibri.ieum.main.admin.content.scheduler;

import java.time.Clock;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import shinhan.fibri.ieum.main.admin.content.repository.AdminContentFileCleanupTaskRepository;
import shinhan.fibri.ieum.main.admin.content.service.FileCleanupTaskProperties;
import shinhan.fibri.ieum.main.admin.content.service.FileCleanupTaskProcessor;

@Component
@ConditionalOnProperty(prefix = "app.file-cleanup", name = "enabled", havingValue = "true", matchIfMissing = true)
public class FileCleanupTaskDispatchScheduler {

	private static final Logger log = LoggerFactory.getLogger(FileCleanupTaskDispatchScheduler.class);

	private final FileCleanupTaskProcessor processor;
	private final AdminContentFileCleanupTaskRepository repository;
	private final FileCleanupTaskProperties properties;
	private final Clock clock;

	public FileCleanupTaskDispatchScheduler(
		FileCleanupTaskProcessor processor,
		AdminContentFileCleanupTaskRepository repository,
		FileCleanupTaskProperties properties
	) {
		this.processor = processor;
		this.repository = repository;
		this.properties = properties;
		this.clock = Clock.systemUTC();
	}

	@Scheduled(
		fixedDelayString = "${app.file-cleanup.poll-delay-ms:1000}",
		initialDelayString = "${app.file-cleanup.poll-initial-delay-ms:1000}"
	)
	public void dispatchDueCleanupTasks() {
		try {
			for (int processed = 0; processed < properties.batchSize() && processor.processNext(); processed++) {
				// continue while task exists
			}
		} catch (RuntimeException failure) {
			log.error(
				"event=file_cleanup_dispatch_failure workerId={} failureType={}",
				properties.workerId(),
				failure.getClass().getSimpleName()
			);
		}
	}

	@Scheduled(
		fixedDelayString = "${app.file-cleanup.recovery-interval-ms:60000}",
		initialDelayString = "${app.file-cleanup.recovery-initial-delay-ms:60000}"
	)
	public void recoverExpiredLeases() {
		try {
			int recovered = repository.recoverExpiredLeases(
				OffsetDateTime.ofInstant(clock.instant(), clock.getZone()),
				properties.maxAttempts()
			);
			if (recovered > 0) {
				log.warn(
					"event=file_cleanup_expired_lease_recovered workerId={} recoveredCount={}",
					properties.workerId(),
					recovered
				);
			}
		} catch (RuntimeException failure) {
			log.error(
				"event=file_cleanup_recovery_failure workerId={} failureType={}",
				properties.workerId(),
				failure.getClass().getSimpleName()
			);
		}
	}
}
