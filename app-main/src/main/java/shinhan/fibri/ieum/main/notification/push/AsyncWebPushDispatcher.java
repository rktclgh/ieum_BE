package shinhan.fibri.ieum.main.notification.push;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import shinhan.fibri.ieum.common.auth.domain.UserSettings;
import shinhan.fibri.ieum.common.auth.domain.UserStatus;
import shinhan.fibri.ieum.common.auth.repository.UserSettingsRepository;
import shinhan.fibri.ieum.main.auth.session.AuthSession;
import shinhan.fibri.ieum.main.auth.session.RedisAuthSessionStore;

public final class AsyncWebPushDispatcher implements WebPushDispatcher {

	private static final Logger log = LoggerFactory.getLogger(AsyncWebPushDispatcher.class);

	private final Executor executor;
	private final UserSettingsRepository settingsRepository;
	private final WebPushSubscriptionRepository subscriptionRepository;
	private final RedisAuthSessionStore sessionStore;
	private final WebPushProviderClient providerClient;

	public AsyncWebPushDispatcher(
		Executor executor,
		UserSettingsRepository settingsRepository,
		WebPushSubscriptionRepository subscriptionRepository,
		RedisAuthSessionStore sessionStore,
		WebPushProviderClient providerClient
	) {
		this.executor = Objects.requireNonNull(executor, "executor");
		this.settingsRepository = Objects.requireNonNull(settingsRepository, "settingsRepository");
		this.subscriptionRepository = Objects.requireNonNull(subscriptionRepository, "subscriptionRepository");
		this.sessionStore = Objects.requireNonNull(sessionStore, "sessionStore");
		this.providerClient = Objects.requireNonNull(providerClient, "providerClient");
	}

	@Override
	public void dispatch(long userId, WebPushDispatchRequest request) {
		if (userId < 1 || request == null) {
			log.warn("event=web_push_dispatch_invalid userId={}", userId);
			return;
		}
		try {
			executor.execute(() -> deliverSafely(userId, request));
		}
		catch (RejectedExecutionException exception) {
			log.warn("event=web_push_dispatch_shed userId={} failureType={}",
				userId, exception.getClass().getSimpleName());
		}
		catch (RuntimeException exception) {
			log.warn("event=web_push_dispatch_submit_failed userId={} failureType={}",
				userId, exception.getClass().getSimpleName());
		}
	}

	private void deliverSafely(long userId, WebPushDispatchRequest request) {
		try {
			Optional<UserSettings> settings = settingsRepository.findById(userId);
			if (settings.isEmpty() || !settings.get().isNotifyAll()) {
				return;
			}

			List<WebPushSubscription> subscriptions = subscriptionRepository.findActiveByUserId(userId);
			for (WebPushSubscription subscription : subscriptions) {
				deliverOne(userId, subscription, request);
			}
		}
		catch (RuntimeException exception) {
			log.warn("event=web_push_dispatch_worker_failed userId={} failureType={}",
				userId, exception.getClass().getSimpleName());
		}
	}

	private void deliverOne(
		long userId,
		WebPushSubscription subscription,
		WebPushDispatchRequest request
	) {
		try {
			Optional<AuthSession> session = sessionStore.findBySessionId(subscription.sessionId());
			if (session.isEmpty()) {
				deleteInvalidBinding(userId, subscription, "missing_session");
				return;
			}

			AuthSession currentSession = session.get();
			if (!Objects.equals(currentSession.userId(), userId)
				|| currentSession.status() != UserStatus.active) {
				deleteInvalidBinding(userId, subscription, "invalid_session");
				return;
			}

			int status = providerClient.send(new WebPushProviderRequest(
				request.payload(),
				subscription.endpoint(),
				subscription.p256dh(),
				subscription.authSecret(),
				request.ttlSeconds(),
				request.topic(),
				request.urgency()
			));
			if (status == 404 || status == 410) {
				boolean deleted = subscriptionRepository.deleteByIdAndBindingVersion(
					subscription.subscriptionId(),
					subscription.bindingVersion()
				);
				log.info(
					"event=web_push_provider_gone userId={} subscriptionId={} bindingVersion={} status={} deleted={}",
					userId,
					subscription.subscriptionId(),
					subscription.bindingVersion(),
					status,
					deleted
				);
			}
			else if (status < 200 || status >= 300) {
				log.warn("event=web_push_provider_retained userId={} subscriptionId={} bindingVersion={} status={}",
					userId, subscription.subscriptionId(), subscription.bindingVersion(), status);
			}
		}
		catch (RuntimeException exception) {
			log.warn("event=web_push_subscription_failed userId={} subscriptionId={} bindingVersion={} failureType={}",
				userId,
				subscription.subscriptionId(),
				subscription.bindingVersion(),
				exception.getClass().getSimpleName());
		}
	}

	private void deleteInvalidBinding(long userId, WebPushSubscription subscription, String reason) {
		boolean deleted = subscriptionRepository.deleteByIdAndBindingVersion(
			subscription.subscriptionId(),
			subscription.bindingVersion()
		);
		log.info("event=web_push_invalid_session_deleted userId={} subscriptionId={} bindingVersion={} reason={} deleted={}",
			userId,
			subscription.subscriptionId(),
			subscription.bindingVersion(),
			reason,
			deleted);
	}
}
