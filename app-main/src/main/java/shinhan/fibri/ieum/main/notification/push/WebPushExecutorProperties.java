package shinhan.fibri.ieum.main.notification.push;

public record WebPushExecutorProperties(
	int threads,
	int queueCapacity,
	int shutdownSeconds
) {

	public WebPushExecutorProperties {
		if (threads < 1) {
			throw new IllegalStateException("app.web-push.executor.threads must be positive");
		}
		if (queueCapacity < 1) {
			throw new IllegalStateException("app.web-push.executor.queue must be positive");
		}
		if (shutdownSeconds < 0) {
			throw new IllegalStateException("app.web-push.executor.shutdown-seconds must be zero or positive");
		}
	}
}
