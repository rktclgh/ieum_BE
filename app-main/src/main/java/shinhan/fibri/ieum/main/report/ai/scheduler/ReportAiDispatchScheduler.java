package shinhan.fibri.ieum.main.report.ai.scheduler;

import java.time.Clock;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import shinhan.fibri.ieum.main.report.ai.service.ReportAiWorkProcessor;
import shinhan.fibri.ieum.main.report.ai.service.ReportAiWorkerProperties;
import shinhan.fibri.ieum.main.report.repository.ReportAiWorkRepository;

@Component
@ConditionalOnProperty(prefix = "app.ai.report", name = "enabled", havingValue = "true")
public class ReportAiDispatchScheduler {

	private static final Logger log = LoggerFactory.getLogger(ReportAiDispatchScheduler.class);

	private final ReportAiWorkProcessor processor;
	private final ReportAiWorkRepository repository;
	private final ReportAiWorkerProperties properties;
	private final Clock clock;

	public ReportAiDispatchScheduler(
		ReportAiWorkProcessor processor,
		ReportAiWorkRepository repository,
		ReportAiWorkerProperties properties,
		Clock clock
	) {
		this.processor = processor;
		this.repository = repository;
		this.properties = properties;
		this.clock = clock;
	}

	@Scheduled(
		fixedDelayString = "${app.ai.report.poll-delay-ms:1000}",
		initialDelayString = "${app.ai.report.poll-initial-delay-ms:1000}"
	)
	public void dispatchDueReports() {
		try {
			for (int processed = 0; processed < properties.batchSize() && processor.processNext(); processed++) {
				// Continue immediately while due work exists; processing remains strictly serial.
			}
		} catch (RuntimeException failure) {
			log.error(
				"event=report_ai_poll_failure workerId={} failureType={}",
				properties.workerId(), failure.getClass().getSimpleName()
			);
		}
	}

	@Scheduled(
		fixedDelayString = "${app.ai.report.recovery-interval-ms:60000}",
		initialDelayString = "${app.ai.report.recovery-initial-delay-ms:60000}"
	)
	public void recoverExpiredLeases() {
		try {
			int recovered = repository.recoverExpiredLeases(
				OffsetDateTime.ofInstant(clock.instant(), clock.getZone()), properties.maxAttempts()
			);
			if (recovered > 0) {
				log.warn(
					"event=report_ai_expired_lease_recovered workerId={} recoveredCount={}",
					properties.workerId(), recovered
				);
			}
		} catch (RuntimeException failure) {
			log.error(
				"event=report_ai_recovery_failure workerId={} failureType={}",
				properties.workerId(), failure.getClass().getSimpleName()
			);
		}
	}
}
