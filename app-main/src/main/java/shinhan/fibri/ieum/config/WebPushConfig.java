package shinhan.fibri.ieum.config;

import com.interaso.webpush.VapidKeys;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import shinhan.fibri.ieum.main.notification.push.InterasoWebPushProviderClient;
import shinhan.fibri.ieum.main.notification.push.WebPushEndpointPolicy;
import shinhan.fibri.ieum.main.notification.push.WebPushProperties;
import shinhan.fibri.ieum.main.notification.push.WebPushProviderClient;
import shinhan.fibri.ieum.main.notification.push.WebPushSubscriptionValidator;
import shinhan.fibri.ieum.main.notification.push.WebPushTransportProperties;

@Configuration
public class WebPushConfig {

	@Bean
	WebPushProperties webPushProperties(
		@Value("${app.web-push.enabled:false}") boolean enabled,
		@Value("${app.web-push.vapid-public-key:}") String vapidPublicKey,
		@Value("${app.web-push.allowed-endpoint-hosts:}") String allowedEndpointHosts
	) {
		return new WebPushProperties(enabled, vapidPublicKey, allowedEndpointHosts);
	}

	@Bean
	WebPushEndpointPolicy webPushEndpointPolicy(WebPushProperties properties) {
		return new WebPushEndpointPolicy(properties.allowedEndpointHosts());
	}

	@Bean
	WebPushSubscriptionValidator webPushSubscriptionValidator(WebPushEndpointPolicy endpointPolicy) {
		return new WebPushSubscriptionValidator(endpointPolicy, Clock.systemUTC());
	}

	@Bean
	WebPushTransportProperties webPushTransportProperties(
		@Value("${app.web-push.vapid-private-key:}") String vapidPrivateKey,
		@Value("${app.web-push.vapid-subject:}") String vapidSubject,
		@Value("${app.web-push.connect-timeout:2s}") String connectTimeout,
		@Value("${app.web-push.request-timeout:5s}") String requestTimeout
	) {
		return new WebPushTransportProperties(
			vapidPrivateKey,
			vapidSubject,
			parseDuration(connectTimeout, "app.web-push.connect-timeout"),
			parseDuration(requestTimeout, "app.web-push.request-timeout")
		);
	}

	@Bean("webPushHttpClient")
	@ConditionalOnProperty(prefix = "app.web-push", name = "enabled", havingValue = "true")
	HttpClient webPushHttpClient(WebPushTransportProperties properties) {
		return HttpClient.newBuilder()
			.connectTimeout(properties.connectTimeout())
			.followRedirects(HttpClient.Redirect.NEVER)
			.build();
	}

	@Bean
	@ConditionalOnProperty(prefix = "app.web-push", name = "enabled", havingValue = "true")
	WebPushProviderClient webPushProviderClient(
		@Qualifier("webPushHttpClient") HttpClient httpClient,
		WebPushProperties webPushProperties,
		WebPushTransportProperties transportProperties,
		WebPushEndpointPolicy endpointPolicy
	) {
		VapidKeys vapidKeys = transportProperties.createVapidKeys(webPushProperties.vapidPublicKey());
		return new InterasoWebPushProviderClient(httpClient, transportProperties, vapidKeys, endpointPolicy);
	}

	private static Duration parseDuration(String value, String propertyName) {
		try {
			return DurationStyle.detectAndParse(value);
		}
		catch (RuntimeException exception) {
			throw new IllegalStateException(propertyName + " must be a valid duration");
		}
	}
}
