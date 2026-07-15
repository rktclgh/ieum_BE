package shinhan.fibri.ieum.ai.report.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class ReportReviewFeatureStatusLogger {

	private static final Logger log = LoggerFactory.getLogger(ReportReviewFeatureStatusLogger.class);

	private final boolean reportReviewEnabled;

	public ReportReviewFeatureStatusLogger(
		@Value("${app.ai.features.report-review-enabled:false}") boolean reportReviewEnabled
	) {
		this.reportReviewEnabled = reportReviewEnabled;
	}

	@EventListener(ApplicationReadyEvent.class)
	public void logStatus() {
		log.info("event=report_review_feature_status enabled={}", reportReviewEnabled);
	}
}
