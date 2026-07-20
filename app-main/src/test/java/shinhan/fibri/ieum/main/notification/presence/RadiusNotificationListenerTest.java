package shinhan.fibri.ieum.main.notification.presence;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import shinhan.fibri.ieum.main.notification.domain.NotificationType;
import shinhan.fibri.ieum.main.notification.message.NotificationMessage;
import shinhan.fibri.ieum.main.notification.message.NotificationMessageKey;
import shinhan.fibri.ieum.main.notification.service.NotificationPublisher;
import shinhan.fibri.ieum.main.friend.service.FriendService;

class RadiusNotificationListenerTest {

	@Test
	void publishesDurableQuestionOnceToResolvedAudienceWhenGateAllows() {
		RadiusNotificationGate gate = mock(RadiusNotificationGate.class);
		RadiusAudienceResolver audienceResolver = mock(RadiusAudienceResolver.class);
		NotificationPublisher publisher = mock(NotificationPublisher.class);
		FriendService friendService = mock(FriendService.class);
		RadiusNotificationListener listener = new RadiusNotificationListener(gate, audienceResolver, publisher, friendService);
		QuestionCreatedEvent event = new QuestionCreatedEvent(10L, 1L, "새 질문", 37.5665, 126.9780);
		when(gate.tryAcquire(NotificationCategory.question, 10L, GeoHashGrid.encode(37.5665, 126.9780))).thenReturn(true);
		when(audienceResolver.resolve(37.5665, 126.9780, NotificationCategory.question, 1L, Set.of())).thenReturn(List.of(2L, 3L));
		when(friendService.blockedUserIdsOf(1L)).thenReturn(Set.of());

		listener.onQuestionCreated(event);
		NotificationMessage message = NotificationMessage.of(
			NotificationMessageKey.RADIUS_QUESTION,
			Map.of("subject", "새 질문")
		);
		verify(publisher).publishDurableOnce(2L, NotificationType.question, message, 10L, null, "radius:question:10");
		verify(publisher).publishDurableOnce(3L, NotificationType.question, message, 10L, null, "radius:question:10");
		verify(publisher, never()).publishEphemeral(2L, NotificationType.question, message, 10L);
	}

	@Test
	void publishesDurableMeetingOnceToResolvedAudienceWhenGateAllows() {
		RadiusNotificationGate gate = mock(RadiusNotificationGate.class);
		RadiusAudienceResolver audienceResolver = mock(RadiusAudienceResolver.class);
		NotificationPublisher publisher = mock(NotificationPublisher.class);
		FriendService friendService = mock(FriendService.class);
		RadiusNotificationListener listener = new RadiusNotificationListener(gate, audienceResolver, publisher, friendService);
		MeetingCreatedEvent event = new MeetingCreatedEvent(20L, 1L, "새 모임", 37.5665, 126.9780);
		when(gate.tryAcquire(NotificationCategory.meeting, 20L, GeoHashGrid.encode(37.5665, 126.9780))).thenReturn(true);
		when(friendService.blockedUserIdsOf(1L)).thenReturn(Set.of());
		when(audienceResolver.resolve(37.5665, 126.9780, NotificationCategory.meeting, 1L, Set.of())).thenReturn(List.of(2L));

		listener.onMeetingCreated(event);
		NotificationMessage message = NotificationMessage.of(
			NotificationMessageKey.RADIUS_MEETING,
			Map.of("subject", "새 모임")
		);
		verify(publisher).publishDurableOnce(
			2L,
			NotificationType.meeting,
			message,
			20L,
			null,
			"radius:meeting:20"
		);
		verify(publisher, never()).publishEphemeral(2L, NotificationType.meeting, message, 20L);
	}
}
