package shinhan.fibri.ieum.main.notification.sse;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class SseInitialFrameWriterTest {

	@Test
	void writesRetryAndConnectedCommentInSingleInitialFrame() throws Exception {
		CapturingSseEmitter emitter = new CapturingSseEmitter();

		new SseInitialFrameWriter().write(emitter, 3_000L);

		assertThat(emitter.frames).anyMatch(frame -> frame.contains("retry:3000"));
		assertThat(emitter.frames).anyMatch(frame -> frame.contains(":connected"));
	}

	private static final class CapturingSseEmitter extends SseEmitter {

		private final List<String> frames = new ArrayList<>();

		@Override
		public void send(SseEventBuilder event) {
			for (ResponseBodyEmitter.DataWithMediaType item : event.build()) {
				frames.add(String.valueOf(item.getData()));
			}
		}
	}
}
