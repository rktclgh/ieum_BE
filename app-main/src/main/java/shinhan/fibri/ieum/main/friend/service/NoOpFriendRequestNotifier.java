package shinhan.fibri.ieum.main.friend.service;

import org.springframework.stereotype.Component;

@Component
public class NoOpFriendRequestNotifier implements FriendRequestNotifier {

	@Override
	public void notifyRequested(Long requesterId, Long addresseeId) {
	}
}
