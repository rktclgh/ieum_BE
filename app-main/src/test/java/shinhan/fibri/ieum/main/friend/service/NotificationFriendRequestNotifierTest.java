package shinhan.fibri.ieum.main.friend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import shinhan.fibri.ieum.common.auth.domain.User;
import shinhan.fibri.ieum.common.auth.repository.UserRepository;
import shinhan.fibri.ieum.main.notification.domain.NotificationType;
import shinhan.fibri.ieum.main.notification.message.NotificationMessage;
import shinhan.fibri.ieum.main.notification.message.NotificationMessageKey;
import shinhan.fibri.ieum.main.notification.service.NotificationPublisher;

class NotificationFriendRequestNotifierTest {

	@Test
	void publishesDurableFriendRequestWithRequesterNickname() {
		UserRepository userRepository = mock(UserRepository.class);
		NotificationPublisher notificationPublisher = mock(NotificationPublisher.class);
		NotificationFriendRequestNotifier notifier = new NotificationFriendRequestNotifier(userRepository, notificationPublisher);
		User requester = mock(User.class);
		when(requester.getNickname()).thenReturn("요청자");
		when(userRepository.findByIdAndDeletedAtIsNull(42L)).thenReturn(Optional.of(requester));

		notifier.notifyRequested(42L, 77L);

		verify(notificationPublisher).publishDurable(
			77L,
			NotificationType.friend,
			NotificationMessage.of(NotificationMessageKey.FRIEND_REQUEST, Map.of("nickname", "요청자")),
			42L
		);
	}

	@Test
	void logsNicknameLookupFailureBeforeUsingFallback() {
		UserRepository userRepository = mock(UserRepository.class);
		NotificationPublisher notificationPublisher = mock(NotificationPublisher.class);
		NotificationFriendRequestNotifier notifier = new NotificationFriendRequestNotifier(userRepository, notificationPublisher);
		Logger logger = (Logger) LoggerFactory.getLogger(NotificationFriendRequestNotifier.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		when(userRepository.findByIdAndDeletedAtIsNull(42L)).thenThrow(new IllegalStateException("database unavailable"));

		try {
			notifier.notifyRequested(42L, 77L);

			verify(notificationPublisher).publishDurable(
				77L,
				NotificationType.friend,
				NotificationMessage.of(NotificationMessageKey.FRIEND_REQUEST, Map.of("nickname", "사용자")),
				42L
			);
			assertThat(appender.list)
				.anySatisfy(event -> {
					assertThat(event.getLevel()).isEqualTo(Level.WARN);
					assertThat(event.getFormattedMessage()).contains("requesterId=42");
					assertThat(event.getThrowableProxy().getMessage()).isEqualTo("database unavailable");
				});
		} finally {
			logger.detachAppender(appender);
			appender.stop();
		}
	}
}
