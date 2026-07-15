package shinhan.fibri.ieum.main.report.ai.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import shinhan.fibri.ieum.main.auth.session.RedisAuthSessionStore;
import shinhan.fibri.ieum.main.notification.push.WebPushSubscriptionCleanup;
import shinhan.fibri.ieum.main.notification.sse.SseConnectionRegistry;
import shinhan.fibri.ieum.main.report.ai.service.ReportAiPostCommitActions;

@Component
@ConditionalOnProperty(prefix = "app.ai.report", name = "enabled", havingValue = "true")
public class DefaultReportAiPostCommitActions implements ReportAiPostCommitActions {

	private static final Logger log = LoggerFactory.getLogger(DefaultReportAiPostCommitActions.class);
	private final RedisAuthSessionStore sessionStore;
	private final WebPushSubscriptionCleanup webPushSubscriptionCleanup;
	private final SseConnectionRegistry sseConnections;

	public DefaultReportAiPostCommitActions(
		RedisAuthSessionStore sessionStore,
		WebPushSubscriptionCleanup webPushSubscriptionCleanup,
		SseConnectionRegistry sseConnections
	) {
		this.sessionStore = sessionStore;
		this.webPushSubscriptionCleanup = webPushSubscriptionCleanup;
		this.sseConnections = sseConnections;
	}

	@Override
	public void schedule(Long userId) {
		if (!TransactionSynchronizationManager.isSynchronizationActive()) {
			execute(userId);
			return;
		}
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				execute(userId);
			}
		});
	}

	private void execute(Long userId) {
		runSafely(
			"report_ai_session_revoke_failed",
			userId,
			() -> sessionStore.revokeAllSessionsOfUser(userId)
		);
		runSafely(
			"report_ai_push_cleanup_failed",
			userId,
			() -> webPushSubscriptionCleanup.deleteForUser(userId)
		);
		runSafely(
			"report_ai_sse_close_failed",
			userId,
			() -> sseConnections.closeUser(userId)
		);
	}

	private void runSafely(String event, Long userId, Runnable action) {
		try {
			action.run();
		} catch (RuntimeException failure) {
			log.error(
				"event={} userId={} failureType={}",
				event,
				userId,
				failure.getClass().getSimpleName()
			);
		}
	}
}
