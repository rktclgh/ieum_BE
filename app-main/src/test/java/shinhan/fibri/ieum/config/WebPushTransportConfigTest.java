package shinhan.fibri.ieum.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpClient;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import shinhan.fibri.ieum.main.notification.push.WebPushProviderClient;
import shinhan.fibri.ieum.main.notification.push.WebPushTestKeys;
import shinhan.fibri.ieum.main.notification.push.WebPushTransportProperties;

class WebPushTransportConfigTest {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withUserConfiguration(WebPushConfig.class);

	@Test
	void disabledConfigurationCreatesNoProviderOrHttpClient() {
		contextRunner.run(context -> {
			assertThat(context).hasSingleBean(WebPushTransportProperties.class);
			assertThat(context).doesNotHaveBean(WebPushProviderClient.class);
			assertThat(context).doesNotHaveBean(HttpClient.class);
		});
	}

	@Test
	void enabledConfigurationCreatesSingletonNonRedirectingClientAndProvider() {
		WebPushTestKeys.RawVapidKeys keys = WebPushTestKeys.generateVapidKeys();

		contextRunner
			.withPropertyValues(
				"app.web-push.enabled=true",
				"app.web-push.vapid-public-key=" + keys.publicKey(),
				"app.web-push.vapid-private-key=" + keys.privateKey(),
				"app.web-push.vapid-subject=mailto:ops@example.com",
				"app.web-push.allowed-endpoint-hosts=push.example.test",
				"app.web-push.connect-timeout=3s",
				"app.web-push.request-timeout=7s"
			)
			.run(context -> {
				assertThat(context).hasSingleBean(WebPushProviderClient.class);
				assertThat(context).hasSingleBean(HttpClient.class);
				HttpClient client = context.getBean(HttpClient.class);
				assertThat(client.followRedirects()).isEqualTo(HttpClient.Redirect.NEVER);
				assertThat(client.connectTimeout()).contains(Duration.ofSeconds(3));
				assertThat(context.getBean(WebPushTransportProperties.class).requestTimeout())
					.isEqualTo(Duration.ofSeconds(7));
			});
	}
}
