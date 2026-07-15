package shinhan.fibri.ieum.main.chat.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "app.web-push", name = "enabled", havingValue = "false", matchIfMissing = true)
public class NoOpChatNotificationPublisher implements ChatNotificationPublisher {

	@Override
	public void messageCreated(ChatPushTrigger trigger) {
	}
}
