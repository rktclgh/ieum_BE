package shinhan.fibri.ieum.main.notification.sse;

import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Component
public class SseInitialFrameWriter {

	public void write(SseEmitter emitter, long retryMs) throws IOException {
		emitter.send(SseEmitter.event()
			.reconnectTime(retryMs)
			.comment("connected"));
	}
}
