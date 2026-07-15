package shinhan.fibri.ieum.main.report.ai.service;

import java.time.Duration;

record ReportAiFailureDisposition(String errorCode, Duration retryDelay) {

	boolean retryable() {
		return retryDelay != null;
	}
}
