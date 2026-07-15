package shinhan.fibri.ieum.main.notification.push;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.interaso.webpush.WebPush;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.Test;
import shinhan.fibri.ieum.common.auth.domain.UserRole;
import shinhan.fibri.ieum.common.auth.domain.UserSettings;
import shinhan.fibri.ieum.common.auth.domain.UserStatus;
import shinhan.fibri.ieum.common.auth.repository.UserSettingsRepository;
import shinhan.fibri.ieum.main.auth.session.AuthSession;
import shinhan.fibri.ieum.main.auth.session.RedisAuthSessionStore;

class AsyncWebPushDispatcherTest {

	private static final long USER_ID = 42L;
	private static final WebPushDispatchRequest REQUEST = new WebPushDispatchRequest(
		"payload".getBytes(StandardCharsets.UTF_8),
		300,
		"topic",
		WebPush.Urgency.Normal
	);

	private final UserSettingsRepository settingsRepository = mock(UserSettingsRepository.class);
	private final WebPushSubscriptionRepository subscriptionRepository = mock(WebPushSubscriptionRepository.class);
	private final RedisAuthSessionStore sessionStore = mock(RedisAuthSessionStore.class);
	private final WebPushProviderClient providerClient = mock(WebPushProviderClient.class);

	@Test
	void missingOrDisabledGlobalPreferenceSkipsSubscriptionLookup() {
		AsyncWebPushDispatcher dispatcher = dispatcher(Runnable::run);
		when(settingsRepository.findById(USER_ID)).thenReturn(Optional.empty());

		dispatcher.dispatch(USER_ID, REQUEST);

		verify(settingsRepository).findById(USER_ID);
		verifyNoInteractions(subscriptionRepository, sessionStore, providerClient);

		UserSettings disabled = mock(UserSettings.class);
		when(disabled.isNotifyAll()).thenReturn(false);
		when(settingsRepository.findById(USER_ID)).thenReturn(Optional.of(disabled));

		dispatcher.dispatch(USER_ID, REQUEST);

		verify(settingsRepository, times(2)).findById(USER_ID);
		verifyNoInteractions(subscriptionRepository, sessionStore, providerClient);
	}

	@Test
	void sendsOnlyForMatchingActiveSessionsAndLazilyDeletesKnownInvalidBindings() {
		WebPushSubscription active = subscription(1L, "active", 11L);
		WebPushSubscription missing = subscription(2L, "missing", 12L);
		WebPushSubscription wrongUser = subscription(3L, "wrong-user", 13L);
		WebPushSubscription suspended = subscription(4L, "suspended", 14L);
		enable(List.of(active, missing, wrongUser, suspended));
		when(sessionStore.findBySessionId("active")).thenReturn(Optional.of(session("active", USER_ID, UserStatus.active)));
		when(sessionStore.findBySessionId("missing")).thenReturn(Optional.empty());
		when(sessionStore.findBySessionId("wrong-user"))
			.thenReturn(Optional.of(session("wrong-user", 99L, UserStatus.active)));
		when(sessionStore.findBySessionId("suspended"))
			.thenReturn(Optional.of(session("suspended", USER_ID, UserStatus.suspended)));
		when(providerClient.send(any())).thenReturn(201);

		dispatcher(Runnable::run).dispatch(USER_ID, REQUEST);

		verify(settingsRepository).findById(USER_ID);
		verify(subscriptionRepository).findActiveByUserId(USER_ID);
		verify(providerClient, times(1)).send(any());
		verify(subscriptionRepository).deleteByIdAndBindingVersion(2L, 12L);
		verify(subscriptionRepository).deleteByIdAndBindingVersion(3L, 13L);
		verify(subscriptionRepository).deleteByIdAndBindingVersion(4L, 14L);
		verify(subscriptionRepository, never()).deleteByIdAndBindingVersion(1L, 11L);
	}

	@Test
	void redisFailureRetainsBindingAndContinuesWithLaterSubscription() {
		WebPushSubscription broken = subscription(1L, "broken", 11L);
		WebPushSubscription active = subscription(2L, "active", 12L);
		enable(List.of(broken, active));
		when(sessionStore.findBySessionId("broken")).thenThrow(new IllegalStateException("redis unavailable"));
		when(sessionStore.findBySessionId("active")).thenReturn(Optional.of(session("active", USER_ID, UserStatus.active)));
		when(providerClient.send(any())).thenReturn(204);

		assertThatCode(() -> dispatcher(Runnable::run).dispatch(USER_ID, REQUEST)).doesNotThrowAnyException();

		verify(providerClient).send(any());
		verify(subscriptionRepository, never()).deleteByIdAndBindingVersion(1L, 11L);
	}

	@Test
	void deletesOnlyGoneProviderResponsesWithBindingFence() {
		List<WebPushSubscription> subscriptions = java.util.stream.LongStream.rangeClosed(1, 11)
			.mapToObj(id -> subscription(id, "sid-" + id, 100L + id))
			.toList();
		enable(subscriptions);
		for (WebPushSubscription subscription : subscriptions) {
			when(sessionStore.findBySessionId(subscription.sessionId()))
				.thenReturn(Optional.of(session(subscription.sessionId(), USER_ID, UserStatus.active)));
		}
		when(providerClient.send(any())).thenReturn(200, 201, 202, 204, 404, 410, 400, 401, 403, 429, 500);

		dispatcher(Runnable::run).dispatch(USER_ID, REQUEST);

		verify(providerClient, times(11)).send(any());
		verify(subscriptionRepository).deleteByIdAndBindingVersion(5L, 105L);
		verify(subscriptionRepository).deleteByIdAndBindingVersion(6L, 106L);
		verify(subscriptionRepository, times(2)).deleteByIdAndBindingVersion(any(Long.class), any(Long.class));
	}

	@Test
	void providerAndFencedDeleteFailuresDoNotBlockLaterSubscriptionsOrBroadenDelete() {
		WebPushSubscription providerFailure = subscription(1L, "one", 11L);
		WebPushSubscription gone = subscription(2L, "two", 12L);
		WebPushSubscription success = subscription(3L, "three", 13L);
		enable(List.of(providerFailure, gone, success));
		for (WebPushSubscription subscription : List.of(providerFailure, gone, success)) {
			when(sessionStore.findBySessionId(subscription.sessionId()))
				.thenReturn(Optional.of(session(subscription.sessionId(), USER_ID, UserStatus.active)));
		}
		when(providerClient.send(any()))
			.thenThrow(new WebPushProviderException("safe failure"))
			.thenReturn(410)
			.thenReturn(204);
		when(subscriptionRepository.deleteByIdAndBindingVersion(2L, 12L)).thenReturn(false);

		assertThatCode(() -> dispatcher(Runnable::run).dispatch(USER_ID, REQUEST)).doesNotThrowAnyException();

		verify(providerClient, times(3)).send(any());
		verify(subscriptionRepository).deleteByIdAndBindingVersion(2L, 12L);
		verify(subscriptionRepository, never()).deleteAllBySessionId(any());
		verify(subscriptionRepository, never()).deleteAllByUserId(any(Long.class));
	}

	@Test
	void preferenceOrSubscriptionFailureDoesNotEscapeWorker() {
		AsyncWebPushDispatcher dispatcher = dispatcher(Runnable::run);
		when(settingsRepository.findById(USER_ID)).thenThrow(new IllegalStateException("db unavailable"));

		assertThatCode(() -> dispatcher.dispatch(USER_ID, REQUEST)).doesNotThrowAnyException();

		UserSettings enabled = mock(UserSettings.class);
		when(enabled.isNotifyAll()).thenReturn(true);
		doReturn(Optional.of(enabled)).when(settingsRepository).findById(USER_ID);
		when(subscriptionRepository.findActiveByUserId(USER_ID)).thenThrow(new IllegalStateException("db unavailable"));

		assertThatCode(() -> dispatcher.dispatch(USER_ID, REQUEST)).doesNotThrowAnyException();
		verifyNoInteractions(sessionStore, providerClient);
	}

	@Test
	void rejectingExecutorShedsWithoutCallerRunsOrProducerException() {
		Executor rejecting = task -> {
			throw new RejectedExecutionException("full");
		};

		assertThatCode(() -> dispatcher(rejecting).dispatch(USER_ID, REQUEST)).doesNotThrowAnyException();

		verifyNoInteractions(settingsRepository, subscriptionRepository, sessionStore, providerClient);
	}

	private AsyncWebPushDispatcher dispatcher(Executor executor) {
		return new AsyncWebPushDispatcher(
			executor,
			settingsRepository,
			subscriptionRepository,
			sessionStore,
			providerClient
		);
	}

	private void enable(List<WebPushSubscription> subscriptions) {
		UserSettings enabled = mock(UserSettings.class);
		when(enabled.isNotifyAll()).thenReturn(true);
		when(settingsRepository.findById(USER_ID)).thenReturn(Optional.of(enabled));
		when(subscriptionRepository.findActiveByUserId(USER_ID)).thenReturn(subscriptions);
	}

	private static WebPushSubscription subscription(long id, String sessionId, long version) {
		OffsetDateTime now = OffsetDateTime.parse("2026-07-15T12:00:00+09:00");
		return new WebPushSubscription(
			id,
			USER_ID,
			sessionId,
			"https://push.example.test/messages/" + id,
			"p256dh-" + id,
			"auth-" + id,
			version,
			null,
			now,
			now
		);
	}

	private static AuthSession session(String sessionId, long userId, UserStatus status) {
		return new AuthSession(
			sessionId,
			userId,
			"user@example.com",
			"refresh-hash",
			null,
			UserRole.user,
			status,
			OffsetDateTime.parse("2026-07-15T12:00:00+09:00")
		);
	}
}
