package shinhan.fibri.ieum.main.notification.sse;

import java.io.IOException;
import java.util.function.Consumer;

interface SseEmitterConnection {

	void send(OutboundEvent event) throws IOException;

	void complete();

	void onCompletion(Runnable callback);

	void onTimeout(Runnable callback);

	void onError(Consumer<Throwable> callback);
}
