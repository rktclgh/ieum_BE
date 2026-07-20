package shinhan.fibri.ieum.main.friend.service;

import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import shinhan.fibri.ieum.common.auth.domain.User;
import shinhan.fibri.ieum.common.auth.repository.UserRepository;
import shinhan.fibri.ieum.main.notification.domain.NotificationType;
import shinhan.fibri.ieum.main.notification.message.NotificationMessage;
import shinhan.fibri.ieum.main.notification.message.NotificationMessageKey;
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
			NotificationMessage.of(
				NotificationMessageKey.FRIEND_REQUEST,
				Map.of("nickname", requesterNickname(requesterId))
			),
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
