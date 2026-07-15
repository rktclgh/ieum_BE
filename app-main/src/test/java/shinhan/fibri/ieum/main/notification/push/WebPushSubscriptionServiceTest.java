package shinhan.fibri.ieum.main.notification.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class WebPushSubscriptionServiceTest {

	private static final Instant NOW = Instant.parse("2026-07-15T00:00:00Z");
	private static final String PUBLIC_VAPID_KEY = WebPushTestKeys.generateVapidKeys().publicKey();
	private WebPushSubscriptionRepository repository;
	private WebPushSubscriptionService service;

	@BeforeEach
	void setUp() {
		repository = mock(WebPushSubscriptionRepository.class);
		WebPushProperties properties = new WebPushProperties(
			true,
			PUBLIC_VAPID_KEY,
			"fcm.googleapis.com"
		);
		WebPushSubscriptionValidator validator = new WebPushSubscriptionValidator(
			properties.allowedEndpointHosts(),
			Clock.fixed(NOW, ZoneOffset.UTC)
		);
		service = new WebPushSubscriptionService(repository, properties, validator);
	}

	@Test
	void disabledConfigReturnsNoKeyOrSubscriptionStateWithoutQueryingRepository() {
		WebPushProperties disabled = new WebPushProperties(false, "ignored-key", "");
		WebPushSubscriptionService disabledService = new WebPushSubscriptionService(
			repository,
			disabled,
			new WebPushSubscriptionValidator(Set.of(), Clock.fixed(NOW, ZoneOffset.UTC))
		);

		WebPushConfigResponse response = disabledService.config(42L, "session-42");

		assertThat(response).isEqualTo(new WebPushConfigResponse(false, "", false));
		verify(repository, never()).existsActiveByUserIdAndSessionId(42L, "session-42");
	}

	@Test
	void enabledConfigUsesExactUserAndSessionBinding() {
		when(repository.existsActiveByUserIdAndSessionId(42L, "session-42")).thenReturn(true);

		WebPushConfigResponse response = service.config(42L, "session-42");

		assertThat(response).isEqualTo(new WebPushConfigResponse(true, PUBLIC_VAPID_KEY, true));
		verify(repository).existsActiveByUserIdAndSessionId(42L, "session-42");
	}

	@Test
	void subscribeMapsValidatedRequestToExactUserAndSession() {
		WebPushSubscriptionRequest request = validRequest();

		service.subscribe(42L, "session-42", request);

		ArgumentCaptor<WebPushSubscriptionInput> captor = ArgumentCaptor.forClass(WebPushSubscriptionInput.class);
		verify(repository).upsert(captor.capture());
		assertThat(captor.getValue().userId()).isEqualTo(42L);
		assertThat(captor.getValue().sessionId()).isEqualTo("session-42");
		assertThat(captor.getValue().endpoint()).isEqualTo(request.endpoint());
		assertThat(captor.getValue().p256dh()).isEqualTo(request.keys().p256dh());
		assertThat(captor.getValue().authSecret()).isEqualTo(request.keys().auth());
	}

	@Test
	void subscribeFailsWhenFeatureIsDisabled() {
		WebPushSubscriptionService disabledService = new WebPushSubscriptionService(
			repository,
			new WebPushProperties(false, "", ""),
			new WebPushSubscriptionValidator(Set.of(), Clock.fixed(NOW, ZoneOffset.UTC))
		);

		assertThatThrownBy(() -> disabledService.subscribe(42L, "session-42", validRequest()))
			.isInstanceOf(WebPushDisabledException.class);
		verify(repository, never()).upsert(org.mockito.ArgumentMatchers.any());
	}

	@Test
	void unsubscribeIsIdempotentAndDeletesOnlyCurrentSession() {
		when(repository.deleteAllBySessionId("session-42")).thenReturn(1, 0);

		service.unsubscribe("session-42");
		service.unsubscribe("session-42");

		verify(repository, times(2)).deleteAllBySessionId("session-42");
	}

	private static WebPushSubscriptionRequest validRequest() {
		byte[] p256dh = new byte[65];
		p256dh[0] = 0x04;
		return new WebPushSubscriptionRequest(
			"https://fcm.googleapis.com/push/123",
			NOW.plusSeconds(60).toEpochMilli(),
			new WebPushSubscriptionRequest.Keys(base64Url(p256dh), base64Url(new byte[16]))
		);
	}

	private static String base64Url(byte[] value) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
	}
}
