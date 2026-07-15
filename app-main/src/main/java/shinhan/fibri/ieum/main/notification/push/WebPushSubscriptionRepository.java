package shinhan.fibri.ieum.main.notification.push;

import java.util.List;

public interface WebPushSubscriptionRepository {

	WebPushSubscription upsert(WebPushSubscriptionInput input);

	boolean deleteByIdAndBindingVersion(long subscriptionId, long bindingVersion);

	int deleteAllBySessionId(String sessionId);

	int deleteAllByUserId(long userId);

	boolean existsActiveByUserIdAndSessionId(long userId, String sessionId);

	List<WebPushSubscription> findActiveByUserId(long userId);
}
