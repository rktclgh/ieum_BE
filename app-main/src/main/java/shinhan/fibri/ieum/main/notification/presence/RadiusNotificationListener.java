package shinhan.fibri.ieum.main.notification.presence;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import shinhan.fibri.ieum.main.friend.service.FriendService;
import shinhan.fibri.ieum.main.notification.domain.NotificationType;
import shinhan.fibri.ieum.main.notification.service.NotificationPublisher;

@Component
public class RadiusNotificationListener {

	private final RadiusNotificationGate gate;
	private final OnlineAudienceResolver audienceResolver;
	private final NotificationPublisher notificationPublisher;
	private final FriendService friendService;

	public RadiusNotificationListener(RadiusNotificationGate gate, OnlineAudienceResolver audienceResolver, NotificationPublisher notificationPublisher, FriendService friendService) {
		this.gate = gate;
		this.audienceResolver = audienceResolver;
		this.notificationPublisher = notificationPublisher;
		this.friendService = friendService;
	}

	@Async("notificationTaskExecutor")
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void onQuestionCreated(QuestionCreatedEvent event) {
		if (!gate.tryAcquire(NotificationCategory.question, event.questionId(), GeoHashGrid.encode(event.latitude(), event.longitude()))) return;
		for (Long userId : audienceResolver.resolve(event.latitude(), event.longitude(), NotificationCategory.question, event.authorId(), friendService.blockedUserIdsOf(event.authorId()))) {
			notificationPublisher.publishEphemeral(userId, NotificationType.question, "주변 새 질문", event.title(), event.questionId());
		}
	}

	@Async("notificationTaskExecutor")
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void onMeetingCreated(MeetingCreatedEvent event) {
		if (!gate.tryAcquire(NotificationCategory.meeting, event.meetingId(), GeoHashGrid.encode(event.latitude(), event.longitude()))) return;
		for (Long userId : audienceResolver.resolve(event.latitude(), event.longitude(), NotificationCategory.meeting, event.hostId(), friendService.blockedUserIdsOf(event.hostId()))) {
			notificationPublisher.publishEphemeral(userId, NotificationType.meeting, "주변 새 모임", event.title(), event.meetingId());
		}
	}
}
