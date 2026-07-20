package shinhan.fibri.ieum.main.notification.sse;

import java.io.IOException;
import java.util.Objects;
import java.util.function.Consumer;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

final class SpringSseEmitterConnection implements SseEmitterConnection {

	private final SseEmitter emitter;

	SpringSseEmitterConnection(SseEmitter emitter) {
		this.emitter = Objects.requireNonNull(emitter, "emitter must not be null");
	}

	@Override
	public void send(OutboundEvent event) throws IOException {
		switch (event.kind()) {
			case durable -> emitter.send(SseEmitter.event()
				.id(String.valueOf(event.notificationPayload().notificationId()))
				.name(event.eventName())
				.data(event.payload()));
			case ephemeral -> emitter.send(SseEmitter.event()
				.name(event.eventName())
				.data(event.payload()));
			case heartbeat -> emitter.send(SseEmitter.event().comment("hb"));
		}
	}

	@Override
	public void complete() {
		emitter.complete();
	}

	@Override
	public void onCompletion(Runnable callback) {
		emitter.onCompletion(callback);
	}

	@Override
	public void onTimeout(Runnable callback) {
		emitter.onTimeout(callback);
	}

	@Override
	public void onError(Consumer<Throwable> callback) {
		emitter.onError(callback);
	}

	boolean wraps(SseEmitter target) {
		return emitter == target;
	}
}
