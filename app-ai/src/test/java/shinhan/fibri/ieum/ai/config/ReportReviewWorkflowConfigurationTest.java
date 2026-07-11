package shinhan.fibri.ieum.ai.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import shinhan.fibri.ieum.ai.report.domain.ReportPolicySnapshot;
import shinhan.fibri.ieum.ai.report.service.PolicySnapshotProvider;
import shinhan.fibri.ieum.ai.report.service.ReportPolicyEvaluator;
import shinhan.fibri.ieum.ai.report.service.ReportReviewInferenceOrchestrator;
import shinhan.fibri.ieum.ai.report.service.ReportReviewModelGateway;

class ReportReviewWorkflowConfigurationTest {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withUserConfiguration(ReportReviewWorkflowConfiguration.class, Dependencies.class);

	@Test
	void doesNotCreateTheReviewWorkflowWhenTheFeatureIsDisabled() {
		contextRunner.run(context -> {
			assertThat(context).doesNotHaveBean(ReportPolicyEvaluator.class);
			assertThat(context).doesNotHaveBean(ReportReviewInferenceOrchestrator.class);
		});
	}

	@Test
	void createsThePolicyEvaluatorAndInferenceOrchestratorWhenEnabled() {
		contextRunner
			.withPropertyValues("app.ai.features.report-review-enabled=true")
			.run(context -> {
				assertThat(context).hasSingleBean(ReportPolicyEvaluator.class);
				assertThat(context).hasSingleBean(ReportReviewInferenceOrchestrator.class);
			});
	}

	@Configuration
	static class Dependencies {

		@Bean
		PolicySnapshotProvider policySnapshotProvider() {
			return () -> new ReportPolicySnapshot("a".repeat(64), java.util.List.of());
		}

		@Bean
		ReportReviewModelGateway reportReviewModelGateway() {
			return (preparedReview, policySnapshot, outputValidator) -> {
				throw new UnsupportedOperationException("not called by this configuration test");
			};
		}
	}
}
