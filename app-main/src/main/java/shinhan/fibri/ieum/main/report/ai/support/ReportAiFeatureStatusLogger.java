package shinhan.fibri.ieum.main.report.ai.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class ReportAiFeatureStatusLogger {

	private static final Logger log = LoggerFactory.getLogger(ReportAiFeatureStatusLogger.class);
	private final boolean enabled;

	public ReportAiFeatureStatusLogger(@Value("${app.ai.report.enabled:false}") boolean enabled) {
		this.enabled = enabled;
	}

	@EventListener(ApplicationReadyEvent.class)
	public void logStatus() {
		log.info("event=report_ai_feature_status enabled={}", enabled);
	}
}
