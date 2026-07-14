package shinhan.fibri.ieum.main.notification.sse;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Component
public class SseEmitterFactory {

	public SseEmitter create(long timeoutMs) {
		return new SseEmitter(timeoutMs);
	}
}
