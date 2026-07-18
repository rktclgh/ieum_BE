package shinhan.fibri.ieum.testsupport;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import shinhan.fibri.ieum.main.notification.presence.UserPresenceQuery;

/**
 * Slice tests import {@code ChatRoomSummaryQueryService} without the SSE layer that owns the
 * production {@link UserPresenceQuery}. Presence is not what those tests assert, so everyone is
 * reported offline.
 */
@TestConfiguration
public class OfflineUserPresenceQueryConfiguration {

	@Bean
	UserPresenceQuery offlineUserPresenceQuery() {
		return userId -> false;
	}
}
