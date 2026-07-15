package shinhan.fibri.ieum.main.notification.sse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import shinhan.fibri.ieum.common.auth.domain.UserRole;
import shinhan.fibri.ieum.common.auth.domain.UserStatus;
import shinhan.fibri.ieum.main.auth.session.AuthSession;
import shinhan.fibri.ieum.main.auth.session.RedisAuthSessionStore;

class SseHeartbeatSchedulerTest {

	private final SseConnectionRegistry registry = mock(SseConnectionRegistry.class);
	private final RedisAuthSessionStore sessionStore = mock(RedisAuthSessionStore.class);
	private final SseHeartbeatScheduler scheduler = new SseHeartbeatScheduler(registry, sessionStore, properties());

	@Test
	void closesMissingInactiveAndMismatchedSessionsWhileKeepingActiveOwnerSession() {
		SseSessionConnection missing = new SseSessionConnection(42L, "sid-missing");
		SseSessionConnection inactive = new SseSessionConnection(43L, "sid-inactive");
		SseSessionConnection mismatch = new SseSessionConnection(44L, "sid-mismatch");
		SseSessionConnection valid = new SseSessionConnection(45L, "sid-valid");
		when(registry.activeSessionsInShard(0, 4)).thenReturn(List.of(missing, inactive, mismatch, valid));
		when(sessionStore.findBySessionId("sid-missing")).thenReturn(Optional.empty());
		when(sessionStore.findBySessionId("sid-inactive")).thenReturn(Optional.of(session("sid-inactive", 43L, UserStatus.suspended)));
		when(sessionStore.findBySessionId("sid-mismatch")).thenReturn(Optional.of(session("sid-mismatch", 999L, UserStatus.active)));
		when(sessionStore.findBySessionId("sid-valid")).thenReturn(Optional.of(session("sid-valid", 45L, UserStatus.active)));

		scheduler.runHeartbeat();

		verify(registry).enqueueHeartbeat();
		verify(registry).closeSession("sid-missing");
		verify(registry).closeSession("sid-inactive");
		verify(registry).closeSession("sid-mismatch");
		verify(registry, never()).closeSession("sid-valid");
	}

	@Test
	void advancesSessionVerificationShardOnEachHeartbeat() {
		when(registry.activeSessionsInShard(anyInt(), eq(4))).thenReturn(List.of());

		scheduler.runHeartbeat();
		scheduler.runHeartbeat();
		scheduler.runHeartbeat();
		scheduler.runHeartbeat();

		ArgumentCaptor<Integer> shard = ArgumentCaptor.forClass(Integer.class);
		verify(registry, times(4)).activeSessionsInShard(shard.capture(), eq(4));
		verify(registry, times(4)).enqueueHeartbeat();
		assertThat(shard.getAllValues()).containsExactly(0, 1, 2, 3);
	}

	private static NotificationProperties properties() {
		return new NotificationProperties(1_800_000L, 5, 32, 15_000L, 4, 3_000L, 8_000L, 4, 16, 500);
	}

	private static AuthSession session(String sessionId, Long userId, UserStatus status) {
		return new AuthSession(
			sessionId,
			userId,
			"user@example.com",
			"refresh-token-hash",
			null,
			UserRole.user,
			status,
			OffsetDateTime.parse("2026-07-10T12:00:00+09:00"),
			0L
		);
	}
}
