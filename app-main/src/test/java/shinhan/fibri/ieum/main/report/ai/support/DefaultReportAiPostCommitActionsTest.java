package shinhan.fibri.ieum.main.report.ai.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import shinhan.fibri.ieum.main.auth.session.RedisAuthSessionStore;
import shinhan.fibri.ieum.main.notification.sse.SseConnectionRegistry;

@ExtendWith(OutputCaptureExtension.class)
class DefaultReportAiPostCommitActionsTest {

	private final RedisAuthSessionStore sessionStore = mock(RedisAuthSessionStore.class);
	private final SseConnectionRegistry sseConnections = mock(SseConnectionRegistry.class);
	private final DefaultReportAiPostCommitActions actions = new DefaultReportAiPostCommitActions(
		sessionStore,
		sseConnections
	);

	@Test
	void closesSseAndLogsTheCauseWhenSessionRevocationFails(CapturedOutput output) {
		doThrow(new IllegalStateException("redis unavailable"))
			.when(sessionStore).revokeAllSessionsOfUser(10L);

		actions.schedule(10L);

		verify(sseConnections).closeUser(10L);
		assertThat(output)
			.contains("event=report_ai_session_revoke_failed")
			.contains("IllegalStateException: redis unavailable");
	}
}
