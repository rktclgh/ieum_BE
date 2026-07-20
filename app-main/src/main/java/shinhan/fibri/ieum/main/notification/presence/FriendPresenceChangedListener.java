package shinhan.fibri.ieum.main.notification.presence;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import shinhan.fibri.ieum.main.friend.service.FriendService;
import shinhan.fibri.ieum.main.notification.sse.OutboundEvent;
import shinhan.fibri.ieum.main.notification.sse.PresenceSsePayload;
import shinhan.fibri.ieum.main.notification.sse.SseConnectionRegistry;

@Component
@RequiredArgsConstructor
public class FriendPresenceChangedListener {

	private static final Logger log = LoggerFactory.getLogger(FriendPresenceChangedListener.class);

	private final FriendService friendService;
	private final SseConnectionRegistry registry;

	@EventListener
	public void onPresenceChanged(UserPresenceChangedEvent event) {
		try {
			OutboundEvent outboundEvent = OutboundEvent.presence(new PresenceSsePayload(event.userId(), event.online()));
			for (Long friendId : friendService.acceptedFriendIdsOf(event.userId())) {
				if (registry.isOnline(friendId)) {
					registry.push(friendId, outboundEvent);
				}
			}
		} catch (RuntimeException exception) {
			log.warn(
				"event=friend_presence_fanout_failed userId={} online={} failureType={}",
				event.userId(),
				event.online(),
				exception.getClass().getSimpleName()
			);
		}
	}
}
