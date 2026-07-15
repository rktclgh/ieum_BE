package shinhan.fibri.ieum.main.notification.push;

import java.util.Objects;
import org.springframework.stereotype.Service;

@Service
public class WebPushSubscriptionService {

	private final WebPushSubscriptionRepository repository;
	private final WebPushProperties properties;
	private final WebPushSubscriptionValidator validator;

	public WebPushSubscriptionService(
		WebPushSubscriptionRepository repository,
		WebPushProperties properties,
		WebPushSubscriptionValidator validator
	) {
		this.repository = Objects.requireNonNull(repository, "repository must not be null");
		this.properties = Objects.requireNonNull(properties, "properties must not be null");
		this.validator = Objects.requireNonNull(validator, "validator must not be null");
	}

	public WebPushConfigResponse config(long userId, String sessionId) {
		if (!properties.enabled()) {
			return new WebPushConfigResponse(false, "", false);
		}
		return new WebPushConfigResponse(
			true,
			properties.vapidPublicKey(),
			repository.existsActiveByUserIdAndSessionId(userId, sessionId)
		);
	}

	public void subscribe(long userId, String sessionId, WebPushSubscriptionRequest request) {
		if (!properties.enabled()) {
			throw new WebPushDisabledException();
		}
		repository.upsert(validator.validate(userId, sessionId, request));
	}

	public void unsubscribe(String sessionId) {
		repository.deleteAllBySessionId(sessionId);
	}
}
