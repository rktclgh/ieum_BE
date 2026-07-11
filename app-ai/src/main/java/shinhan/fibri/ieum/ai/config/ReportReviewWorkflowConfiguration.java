package shinhan.fibri.ieum.ai.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import shinhan.fibri.ieum.ai.report.service.PolicySnapshotProvider;
import shinhan.fibri.ieum.ai.report.service.ReportPolicyEvaluator;
import shinhan.fibri.ieum.ai.report.service.ReportReviewInferenceOrchestrator;
import shinhan.fibri.ieum.ai.report.service.ReportReviewModelGateway;

@Configuration
@ConditionalOnProperty(prefix = "app.ai.features", name = "report-review-enabled", havingValue = "true")
public class ReportReviewWorkflowConfiguration {

	@Bean
	ReportPolicyEvaluator reportPolicyEvaluator() {
		return new ReportPolicyEvaluator();
	}

	@Bean
	ReportReviewInferenceOrchestrator reportReviewInferenceOrchestrator(
		PolicySnapshotProvider policySnapshotProvider,
		ReportReviewModelGateway reportReviewModelGateway,
		ReportPolicyEvaluator reportPolicyEvaluator
	) {
		return new ReportReviewInferenceOrchestrator(
			policySnapshotProvider,
			reportReviewModelGateway,
			reportPolicyEvaluator,
			new ObjectMapper()
		);
	}
}
