package shinhan.fibri.ieum.ai.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import shinhan.fibri.ieum.ai.report.service.ReportEvidenceImageDownloader;
import shinhan.fibri.ieum.ai.report.service.ReportEvidenceImageUrlValidator;
import shinhan.fibri.ieum.ai.report.service.ReportEvidenceImageBatchCollector;

class ReportEvidenceImageConfigurationTest {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
		.withUserConfiguration(ReportEvidenceImageConfiguration.class);

	@Test
	void normalizesImageHostsAndBuildsANoRedirectDownloader() {
		ReportReviewProperties properties = properties(Set.of(" IEUM-FILES.S3.AP-NORTHEAST-2.AMAZONAWS.COM "));
		ReportEvidenceImageConfiguration configuration = new ReportEvidenceImageConfiguration();

		ReportEvidenceImageUrlValidator validator = configuration.reportEvidenceImageUrlValidator(properties);
		var client = configuration.reportEvidenceImageHttpClient(properties);
		ReportEvidenceImageDownloader downloader = configuration.reportEvidenceImageDownloader(client, validator, properties);

		assertThat(properties.imageAllowedHosts()).containsExactly("ieum-files.s3.ap-northeast-2.amazonaws.com");
		assertThat(client.followRedirects()).isEqualTo(java.net.http.HttpClient.Redirect.NEVER);
		assertThat(downloader).isNotNull();
	}

	@Test
	void rejectsMissingHostsAndAnInvalidImageBudget() {
		assertThatThrownBy(() -> properties(Set.of()))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("imageAllowedHosts");
		assertThatThrownBy(() -> new ReportReviewProperties(
			3_750_000L,
			3_749_999L,
			Set.of("ieum-files.s3.ap-northeast-2.amazonaws.com"),
			Duration.ofSeconds(5)
		))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("imageMaxTotalBytes");
		assertThatThrownBy(() -> new ReportReviewProperties(
			3_750_001L,
			15_000_000L,
			Set.of("ieum-files.s3.ap-northeast-2.amazonaws.com"),
			Duration.ofSeconds(5)
		))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("imageMaxBytes");
		assertThatThrownBy(() -> new ReportReviewProperties(
			3_750_000L,
			15_000_001L,
			Set.of("ieum-files.s3.ap-northeast-2.amazonaws.com"),
			Duration.ofSeconds(5)
		))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("imageMaxTotalBytes");
	}

	@Test
	void createsTheDownloaderOnlyWhenReportReviewIsEnabled() {
		contextRunner.run(context -> assertThat(context).doesNotHaveBean(ReportEvidenceImageDownloader.class));

		contextRunner
			.withPropertyValues(
				"app.ai.features.report-review-enabled=true",
				"app.ai.report.image-max-bytes=3750000",
				"app.ai.report.image-max-total-bytes=15000000",
				"app.ai.report.image-allowed-hosts=ieum-files.s3.ap-northeast-2.amazonaws.com",
				"app.ai.report.image-download-timeout=5s"
			)
			.run(context -> {
				assertThat(context).hasSingleBean(ReportEvidenceImageDownloader.class);
				assertThat(context).hasSingleBean(ReportEvidenceImageUrlValidator.class);
				assertThat(context).hasSingleBean(ReportEvidenceImageBatchCollector.class);
			});
	}

	private ReportReviewProperties properties(Set<String> hosts) {
		return new ReportReviewProperties(3_750_000L, 15_000_000L, hosts, Duration.ofSeconds(5));
	}
}
