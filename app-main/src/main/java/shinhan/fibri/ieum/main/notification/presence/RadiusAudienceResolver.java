package shinhan.fibri.ieum.main.notification.presence;

import java.util.List;
import java.util.Set;

public interface RadiusAudienceResolver {

	List<Long> resolve(double latitude, double longitude, NotificationCategory category, Long authorId, Set<Long> blockedUserIds);
}
