package shinhan.fibri.ieum.main.report.ai.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import shinhan.fibri.ieum.main.auth.session.RedisAuthSessionStore;
import shinhan.fibri.ieum.main.notification.push.WebPushSubscriptionCleanup;
import shinhan.fibri.ieum.main.notification.sse.SseConnectionRegistry;

@ExtendWith(OutputCaptureExtension.class)
class DefaultReportAiPostCommitActionsTest {

	private final RedisAuthSessionStore sessionStore = mock(RedisAuthSessionStore.class);
	private final WebPushSubscriptionCleanup webPushSubscriptionCleanup = mock(WebPushSubscriptionCleanup.class);
	private final SseConnectionRegistry sseConnections = mock(SseConnectionRegistry.class);
	private final DefaultReportAiPostCommitActions actions = new DefaultReportAiPostCommitActions(
		sessionStore,
		webPushSubscriptionCleanup,
		sseConnections
	);

	@AfterEach
	void clearSynchronization() {
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.clearSynchronization();
		}
	}

	@Test
	void runsRemainingInvalidationActionsAndLogsOnlySafeFieldsWhenSessionRevocationFails(CapturedOutput output) {
		doThrow(new IllegalStateException("redis unavailable"))
			.when(sessionStore).revokeAllSessionsOfUser(10L);

		actions.schedule(10L);

		verify(webPushSubscriptionCleanup).deleteForUser(10L);
		verify(sseConnections).closeUser(10L);
		assertThat(output)
			.contains("event=report_ai_session_revoke_failed")
			.contains("failureType=IllegalStateException")
			.doesNotContain("redis unavailable");
	}

	@Test
	void closesSseWhenPushCleanupFails(CapturedOutput output) {
		doThrow(new IllegalStateException("database unavailable"))
			.when(webPushSubscriptionCleanup).deleteForUser(10L);

		actions.schedule(10L);

		verify(sessionStore).revokeAllSessionsOfUser(10L);
		verify(webPushSubscriptionCleanup).deleteForUser(10L);
		verify(sseConnections).closeUser(10L);
		assertThat(output)
			.contains("event=report_ai_push_cleanup_failed")
			.contains("failureType=IllegalStateException")
			.doesNotContain("database unavailable");
	}

	@Test
	void runsInvalidationActionsOnlyAfterCommit() {
		TransactionSynchronizationManager.initSynchronization();

		actions.schedule(10L);

		verify(sessionStore, never()).revokeAllSessionsOfUser(10L);
		verify(webPushSubscriptionCleanup, never()).deleteForUser(10L);
		verify(sseConnections, never()).closeUser(10L);

		TransactionSynchronizationManager.getSynchronizations()
			.forEach(TransactionSynchronization::afterCommit);

		verify(sessionStore).revokeAllSessionsOfUser(10L);
		verify(webPushSubscriptionCleanup).deleteForUser(10L);
		verify(sseConnections).closeUser(10L);
	}

	@Test
	void doesNotRunInvalidationActionsWhenTransactionRollsBack() {
		TransactionSynchronizationManager.initSynchronization();

		actions.schedule(10L);
		TransactionSynchronizationManager.getSynchronizations()
			.forEach(synchronization -> synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

		verify(sessionStore, never()).revokeAllSessionsOfUser(10L);
		verify(webPushSubscriptionCleanup, never()).deleteForUser(10L);
		verify(sseConnections, never()).closeUser(10L);
	}
}
