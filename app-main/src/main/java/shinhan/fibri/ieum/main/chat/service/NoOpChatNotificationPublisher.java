package shinhan.fibri.ieum.main.chat.service;

import org.springframework.stereotype.Component;

@Component
public class NoOpChatNotificationPublisher implements ChatNotificationPublisher {

	@Override
	public void messageCreated(WsMessageEvent event) {
	}
}
