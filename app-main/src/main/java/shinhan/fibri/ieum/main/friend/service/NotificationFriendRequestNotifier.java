package shinhan.fibri.ieum.main.friend.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import shinhan.fibri.ieum.common.auth.domain.User;
import shinhan.fibri.ieum.common.auth.repository.UserRepository;
import shinhan.fibri.ieum.main.notification.domain.NotificationType;
import shinhan.fibri.ieum.main.notification.service.NotificationPublisher;

@Component
@Slf4j
public class NotificationFriendRequestNotifier implements FriendRequestNotifier {

	private final UserRepository userRepository;
	private final NotificationPublisher notificationPublisher;

	public NotificationFriendRequestNotifier(UserRepository userRepository, NotificationPublisher notificationPublisher) {
		this.userRepository = userRepository;
		this.notificationPublisher = notificationPublisher;
	}

	@Override
	public void notifyRequested(Long requesterId, Long addresseeId) {
		notificationPublisher.publishDurable(
			addresseeId,
			NotificationType.friend,
			"친구 요청",
			requesterNickname(requesterId) + "님이 친구 요청을 보냈어요",
			requesterId
		);
	}

	private String requesterNickname(Long requesterId) {
		try {
			return userRepository.findByIdAndDeletedAtIsNull(requesterId)
				.map(User::getNickname)
				.orElse("사용자");
		} catch (RuntimeException exception) {
			log.warn("Failed to resolve nickname for requesterId={}", requesterId, exception);
			return "사용자";
		}
	}
}
