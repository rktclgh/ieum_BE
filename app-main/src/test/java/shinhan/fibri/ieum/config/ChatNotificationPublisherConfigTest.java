package shinhan.fibri.ieum.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import shinhan.fibri.ieum.common.chat.repository.ChatMemberRepository;
import shinhan.fibri.ieum.main.chat.service.ChatNotificationPublisher;
import shinhan.fibri.ieum.main.chat.service.NoOpChatNotificationPublisher;
import shinhan.fibri.ieum.main.chat.service.WebPushChatNotificationPublisher;
import shinhan.fibri.ieum.main.notification.push.WebPushDispatcher;
import shinhan.fibri.ieum.main.notification.push.WebPushPayloadEncoder;

class ChatNotificationPublisherConfigTest {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withUserConfiguration(NoOpChatNotificationPublisher.class, WebPushChatNotificationPublisher.class)
		.withBean(ChatMemberRepository.class, () -> mock(ChatMemberRepository.class))
		.withBean(WebPushPayloadEncoder.class, () -> mock(WebPushPayloadEncoder.class))
		.withBean(WebPushDispatcher.class, () -> mock(WebPushDispatcher.class));

	@Test
	void disabledOrMissingConfigurationCreatesOnlyNoOpPublisher() {
		contextRunner.run(context -> {
			assertThat(context).hasSingleBean(ChatNotificationPublisher.class);
			assertThat(context.getBean(ChatNotificationPublisher.class))
				.isInstanceOf(NoOpChatNotificationPublisher.class);
		});
	}

	@Test
	void enabledConfigurationCreatesOnlyWebPushPublisher() {
		contextRunner
			.withPropertyValues("app.web-push.enabled=true")
			.run(context -> {
				assertThat(context).hasSingleBean(ChatNotificationPublisher.class);
				assertThat(context.getBean(ChatNotificationPublisher.class))
					.isInstanceOf(WebPushChatNotificationPublisher.class);
			});
	}
}
