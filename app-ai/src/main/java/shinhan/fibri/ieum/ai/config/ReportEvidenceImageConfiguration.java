package shinhan.fibri.ieum.ai.config;

import java.net.http.HttpClient;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import shinhan.fibri.ieum.ai.report.service.ReportEvidenceImageDownloader;

import shinhan.fibri.ieum.ai.report.service.ReportEvidenceImageBatchCollector;
import shinhan.fibri.ieum.ai.report.service.ReportEvidenceImageUrlValidator;

@Configuration
@ConditionalOnProperty(prefix = "app.ai.features", name = "report-review-enabled", havingValue = "true")
@EnableConfigurationProperties(ReportReviewProperties.class)
public class ReportEvidenceImageConfiguration {

	@Bean
	ReportEvidenceImageUrlValidator reportEvidenceImageUrlValidator(ReportReviewProperties properties) {
		return new ReportEvidenceImageUrlValidator(properties.imageAllowedHosts());
	}

	@Bean
	HttpClient reportEvidenceImageHttpClient(ReportReviewProperties properties) {
		return HttpClient.newBuilder()
			.connectTimeout(properties.imageDownloadTimeout())
			.followRedirects(HttpClient.Redirect.NEVER)
			.build();
	}

	@Bean(destroyMethod = "close")
	ExecutorService reportEvidenceImageDownloadExecutor() {
		return Executors.newVirtualThreadPerTaskExecutor();
	}

	@Bean
	ReportEvidenceImageDownloader reportEvidenceImageDownloader(
		HttpClient reportEvidenceImageHttpClient,
		ReportEvidenceImageUrlValidator reportEvidenceImageUrlValidator,
		ReportReviewProperties properties,
		ExecutorService reportEvidenceImageDownloadExecutor
	) {
		return new ReportEvidenceImageDownloader(
			reportEvidenceImageHttpClient,
			reportEvidenceImageUrlValidator,
			properties.imageMaxBytes(),
			properties.imageDownloadTimeout(),
			reportEvidenceImageDownloadExecutor
		);
	}

	@Bean
	ReportEvidenceImageBatchCollector reportEvidenceImageBatchCollector(
		ReportEvidenceImageDownloader reportEvidenceImageDownloader,
		ReportReviewProperties properties
	) {
		return new ReportEvidenceImageBatchCollector(
			reportEvidenceImageDownloader,
			properties.imageMaxBytes(),
			properties.imageMaxTotalBytes()
		);
	}
}
