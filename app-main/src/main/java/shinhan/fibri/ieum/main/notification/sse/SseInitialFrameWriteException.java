package shinhan.fibri.ieum.main.notification.sse;

public class SseInitialFrameWriteException extends RuntimeException {

	public SseInitialFrameWriteException(Throwable cause) {
		super("Failed to initialize SSE stream", cause);
	}
}
