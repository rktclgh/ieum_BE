package shinhan.fibri.ieum.main.notification.sse;

import java.io.IOException;
import java.util.concurrent.ThreadLocalRandom;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import shinhan.fibri.ieum.main.auth.session.SessionTokenValidator;
import shinhan.fibri.ieum.main.auth.session.ValidatedAuthSession;

@Service
@RequiredArgsConstructor
public class SseSubscriptionService {

	private final SessionTokenValidator sessionTokenValidator;
	private final SseConnectionRegistry registry;
	private final SseEmitterFactory emitterFactory;
	private final SseInitialFrameWriter initialFrameWriter;
	private final NotificationProperties properties;

	public SseEmitter subscribe(String accessToken) {
		ValidatedAuthSession session = validate(accessToken);
		SseEmitter emitter = emitterFactory.create(properties.sseTimeoutMs());
		try {
			initialFrameWriter.write(emitter, jitteredRetryMs());
		} catch (IOException exception) {
			emitter.completeWithError(exception);
			throw new SseInitialFrameWriteException(exception);
		}
		registry.register(session.principal().userId(), session.sessionId(), emitter);
		return emitter;
	}

	private ValidatedAuthSession validate(String accessToken) {
		if (accessToken == null || accessToken.isBlank()) {
			throw new SseAuthenticationRequiredException();
		}
		return sessionTokenValidator.validateSession(accessToken)
			.orElseThrow(SseAuthenticationRequiredException::new);
	}

	private long jitteredRetryMs() {
		return ThreadLocalRandom.current().nextLong(properties.retryMinMs(), properties.retryMaxMs() + 1);
	}
}
