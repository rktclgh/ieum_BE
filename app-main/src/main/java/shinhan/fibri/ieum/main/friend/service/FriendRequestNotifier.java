package shinhan.fibri.ieum.main.friend.service;

public interface FriendRequestNotifier {

	void notifyRequested(Long requesterId, Long addresseeId);
}
