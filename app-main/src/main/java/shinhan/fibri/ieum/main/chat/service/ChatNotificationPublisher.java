package shinhan.fibri.ieum.main.chat.service;

public interface ChatNotificationPublisher {

	void messageCreated(ChatPushTrigger trigger);
}
