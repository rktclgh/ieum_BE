package shinhan.fibri.ieum.main.admin.content.service;

import java.time.Duration;

public final class FileCleanupTaskRetryPolicy {

	private FileCleanupTaskRetryPolicy() {
	}

	static FileCleanupFailureDisposition classify(RuntimeException failure, int attempts, int maxAttempts) {
		if (attempts >= maxAttempts) {
			return new FileCleanupFailureDisposition("MAX_ATTEMPTS_EXCEEDED", null);
		}
		if (failure == null) {
			return new FileCleanupFailureDisposition("UNKNOWN", null);
		}
		long delaySeconds = 30L * (long) Math.pow(2, Math.max(0, attempts - 1));
		return new FileCleanupFailureDisposition("S3_DELETE_RETRY", Duration.ofSeconds(Math.min(delaySeconds, 3_600)));
	}

	record FileCleanupFailureDisposition(String errorCode, Duration retryDelay) {
		boolean retryable() {
			return retryDelay != null;
		}
	}
}
