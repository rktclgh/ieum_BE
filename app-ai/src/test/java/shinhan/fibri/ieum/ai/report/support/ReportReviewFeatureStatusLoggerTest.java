package shinhan.fibri.ieum.ai.report.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class ReportReviewFeatureStatusLoggerTest {

	@Test
	void logsOneInfoLineWhenReportReviewFeatureIsDisabled(CapturedOutput output) {
		new ReportReviewFeatureStatusLogger(false).logStatus();

		assertThat(output)
			.contains("event=report_review_feature_status")
			.contains("enabled=false");
	}

	@Test
	void logsOneInfoLineWhenReportReviewFeatureIsEnabled(CapturedOutput output) {
		new ReportReviewFeatureStatusLogger(true).logStatus();

		assertThat(output)
			.contains("event=report_review_feature_status")
			.contains("enabled=true");
	}
}
