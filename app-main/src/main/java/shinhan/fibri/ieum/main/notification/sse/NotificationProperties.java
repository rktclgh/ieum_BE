package shinhan.fibri.ieum.main.notification.sse;

public record NotificationProperties(
	long sseTimeoutMs,
	int maxConnectionsPerUser,
	int durableQueuePerEmitter,
	long heartbeatMs,
	int sessionCheckShards,
	long retryMinMs,
	long retryMaxMs,
	int dispatchCorePoolSize,
	int dispatchMaxPoolSize,
	int dispatchQueueCapacity
) {

	public NotificationProperties {
		requirePositive(sseTimeoutMs, "sseTimeoutMs");
		requirePositive(maxConnectionsPerUser, "maxConnectionsPerUser");
		requirePositive(durableQueuePerEmitter, "durableQueuePerEmitter");
		requirePositive(heartbeatMs, "heartbeatMs");
		requirePositive(sessionCheckShards, "sessionCheckShards");
		requirePositive(retryMinMs, "retryMinMs");
		requirePositive(retryMaxMs, "retryMaxMs");
		requirePositive(dispatchCorePoolSize, "dispatchCorePoolSize");
		requirePositive(dispatchMaxPoolSize, "dispatchMaxPoolSize");
		requirePositive(dispatchQueueCapacity, "dispatchQueueCapacity");
		if (retryMinMs > retryMaxMs) {
			throw new IllegalArgumentException("retryMinMs must not exceed retryMaxMs");
		}
		if (dispatchCorePoolSize > dispatchMaxPoolSize) {
			throw new IllegalArgumentException("dispatchCorePoolSize must not exceed dispatchMaxPoolSize");
		}
	}

	private static void requirePositive(long value, String name) {
		if (value < 1) {
			throw new IllegalArgumentException(name + " must be positive");
		}
	}
}
