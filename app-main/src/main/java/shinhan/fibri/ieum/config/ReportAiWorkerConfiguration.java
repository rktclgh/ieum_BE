package shinhan.fibri.ieum.config;

import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import shinhan.fibri.ieum.main.report.ai.service.ReportAiRetryPolicy;
import shinhan.fibri.ieum.main.report.ai.service.ReportAiWorkerProperties;

@Configuration
@ConditionalOnProperty(prefix = "app.ai.report", name = "enabled", havingValue = "true")
public class ReportAiWorkerConfiguration {

	@Bean
	ReportAiWorkerProperties reportAiWorkerProperties(
		@Value("${app.ai.report.worker-id:${HOSTNAME:local}-${random.uuid}}") String workerId,
		@Value("${app.ai.report.lease:150s}") Duration lease,
		@Value("${app.ai.report.max-attempts:5}") int maxAttempts,
		@Value("${app.ai.report.batch-size:32}") int batchSize
	) {
		return new ReportAiWorkerProperties(workerId, lease, maxAttempts, batchSize);
	}

	@Bean
	ReportAiRetryPolicy reportAiRetryPolicy() {
		return new ReportAiRetryPolicy();
	}

	@Bean
	Clock reportAiClock() {
		return Clock.systemUTC();
	}
}
