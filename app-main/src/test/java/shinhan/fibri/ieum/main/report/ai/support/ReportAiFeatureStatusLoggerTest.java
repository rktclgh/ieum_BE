package shinhan.fibri.ieum.main.report.ai.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class ReportAiFeatureStatusLoggerTest {

	@Test
	void logsFeatureStatusWhenReportAiIsDisabled(CapturedOutput output) {
		new ReportAiFeatureStatusLogger(false).logStatus();

		assertThat(output)
			.contains("event=report_ai_feature_status")
			.contains("enabled=false");
	}

	@Test
	void logsFeatureStatusWhenReportAiIsEnabled(CapturedOutput output) {
		new ReportAiFeatureStatusLogger(true).logStatus();

		assertThat(output)
			.contains("event=report_ai_feature_status")
			.contains("enabled=true");
	}
}
