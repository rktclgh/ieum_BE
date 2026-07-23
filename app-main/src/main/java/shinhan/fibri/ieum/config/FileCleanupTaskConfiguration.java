package shinhan.fibri.ieum.config;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import shinhan.fibri.ieum.main.admin.content.service.FileCleanupTaskProperties;

@Configuration
public class FileCleanupTaskConfiguration {

	@Bean
	FileCleanupTaskProperties fileCleanupTaskProperties(
		@Value("${app.file-cleanup.worker-id:${HOSTNAME:local}-${random.uuid}}") String workerId,
		@Value("${app.file-cleanup.lease:120s}") Duration lease,
		@Value("${app.file-cleanup.max-attempts:8}") int maxAttempts,
		@Value("${app.file-cleanup.batch-size:32}") int batchSize
	) {
		return new FileCleanupTaskProperties(workerId, lease, maxAttempts, batchSize);
	}
}
