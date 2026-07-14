package shinhan.fibri.ieum.main.notification.presence;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import shinhan.fibri.ieum.main.notification.domain.NotificationType;
import shinhan.fibri.ieum.main.notification.service.NotificationPublisher;
import shinhan.fibri.ieum.main.friend.service.FriendService;

class RadiusNotificationListenerTest {

	@Test
	void publishesEphemeralQuestionToResolvedAudienceWhenGateAllows() {
		RadiusNotificationGate gate = mock(RadiusNotificationGate.class);
		OnlineAudienceResolver audienceResolver = mock(OnlineAudienceResolver.class);
		NotificationPublisher publisher = mock(NotificationPublisher.class);
		FriendService friendService = mock(FriendService.class);
		RadiusNotificationListener listener = new RadiusNotificationListener(gate, audienceResolver, publisher, friendService);
		QuestionCreatedEvent event = new QuestionCreatedEvent(10L, 1L, "새 질문", 37.5665, 126.9780);
		when(gate.tryAcquire(NotificationCategory.question, 10L, GeoHashGrid.encode(37.5665, 126.9780))).thenReturn(true);
		when(audienceResolver.resolve(37.5665, 126.9780, NotificationCategory.question, 1L, Set.of())).thenReturn(List.of(2L, 3L));
		when(friendService.blockedUserIdsOf(1L)).thenReturn(Set.of());

		listener.onQuestionCreated(event);

		verify(publisher).publishEphemeral(2L, NotificationType.question, "주변 새 질문", "새 질문", 10L);
		verify(publisher).publishEphemeral(3L, NotificationType.question, "주변 새 질문", "새 질문", 10L);
	}

	@Test
	void publishesEphemeralMeetingToResolvedAudienceWhenGateAllows() {
		RadiusNotificationGate gate = mock(RadiusNotificationGate.class);
		OnlineAudienceResolver audienceResolver = mock(OnlineAudienceResolver.class);
		NotificationPublisher publisher = mock(NotificationPublisher.class);
		FriendService friendService = mock(FriendService.class);
		RadiusNotificationListener listener = new RadiusNotificationListener(gate, audienceResolver, publisher, friendService);
		MeetingCreatedEvent event = new MeetingCreatedEvent(20L, 1L, "새 모임", 37.5665, 126.9780);
		when(gate.tryAcquire(NotificationCategory.meeting, 20L, GeoHashGrid.encode(37.5665, 126.9780))).thenReturn(true);
		when(friendService.blockedUserIdsOf(1L)).thenReturn(Set.of());
		when(audienceResolver.resolve(37.5665, 126.9780, NotificationCategory.meeting, 1L, Set.of())).thenReturn(List.of(2L));

		listener.onMeetingCreated(event);

		verify(publisher).publishEphemeral(2L, NotificationType.meeting, "주변 새 모임", "새 모임", 20L);
	}
}
