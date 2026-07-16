package shinhan.fibri.ieum.ai.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class ReportModelPropertiesTest {

	@Test
	void acceptsTheConfiguredGeminiAndSydneyBedrockModels() {
		ReportModelProperties properties = new ReportModelProperties(
			"gemini-3.1-flash-lite",
			"amazon.nova-lite-v1:0",
			"ap-southeast-2",
			Duration.ofSeconds(30),
			"report-review-v1"
		);

		assertThat(properties.geminiModel()).isEqualTo("gemini-3.1-flash-lite");
		assertThat(properties.novaModel()).isEqualTo("amazon.nova-lite-v1:0");
		assertThat(properties.bedrockRegion()).isEqualTo("ap-southeast-2");
	}

	@Test
	void rejectsANonSydneyBedrockRegion() {
		assertThatThrownBy(() -> new ReportModelProperties(
			"gemini-3.1-flash-lite", "amazon.nova-lite-v1:0", "ap-northeast-2", Duration.ofSeconds(30), "report-review-v1"
		))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("bedrockRegion");
	}

	@Test
	void rejectsBlankModelConfigurationAndNonPositiveTimeout() {
		assertThatThrownBy(() -> new ReportModelProperties(
			" ", "amazon.nova-lite-v1:0", "ap-southeast-2", Duration.ofSeconds(30), "report-review-v1"
		))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("geminiModel");
		assertThatThrownBy(() -> new ReportModelProperties(
			"gemini-3.1-flash-lite", "amazon.nova-lite-v1:0", "ap-southeast-2", Duration.ZERO, "report-review-v1"
		))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("modelTimeout");
	}
}
