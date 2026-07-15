package shinhan.fibri.ieum.main.notification.sse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import shinhan.fibri.ieum.common.auth.domain.UserRole;
import shinhan.fibri.ieum.common.auth.domain.UserStatus;
import shinhan.fibri.ieum.common.auth.repository.UserAuthState;
import shinhan.fibri.ieum.common.auth.repository.UserRepository;
import shinhan.fibri.ieum.main.auth.session.AuthSession;
import shinhan.fibri.ieum.main.auth.session.CanonicalAuthStateVerifier;
import shinhan.fibri.ieum.main.auth.session.RedisAuthSessionStore;

class SseHeartbeatSchedulerTest {

	private final SseConnectionRegistry registry = mock(SseConnectionRegistry.class);
	private final RedisAuthSessionStore sessionStore = mock(RedisAuthSessionStore.class);
	private final UserRepository userRepository = mock(UserRepository.class);
	private final CanonicalAuthStateVerifier canonicalAuthStateVerifier = new CanonicalAuthStateVerifier(userRepository);
	private final SseHeartbeatScheduler scheduler = new SseHeartbeatScheduler(
		registry,
		sessionStore,
		canonicalAuthStateVerifier,
		properties()
	);

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
		when(userRepository.findAuthStateById(43L)).thenReturn(Optional.of(canonical(43L, UserRole.user, UserStatus.active, 0L)));
		when(userRepository.findAuthStateById(45L)).thenReturn(Optional.of(canonical(45L, UserRole.user, UserStatus.active, 0L)));

		scheduler.runHeartbeat();

		verify(registry).enqueueHeartbeat();
		verify(registry).closeSession("sid-missing");
		verify(registry).closeSession("sid-inactive");
		verify(registry).closeSession("sid-mismatch");
		verify(registry, never()).closeSession("sid-valid");
	}

	@Test
	void closesCanonicalVersionRoleAndStatusMismatchesWhileKeepingExactMatch() {
		SseSessionConnection versionMismatch = new SseSessionConnection(50L, "sid-version");
		SseSessionConnection roleMismatch = new SseSessionConnection(51L, "sid-role");
		SseSessionConnection statusMismatch = new SseSessionConnection(52L, "sid-status");
		SseSessionConnection valid = new SseSessionConnection(53L, "sid-valid");
		when(registry.activeSessionsInShard(0, 4))
			.thenReturn(List.of(versionMismatch, roleMismatch, statusMismatch, valid));
		when(sessionStore.findBySessionId("sid-version"))
			.thenReturn(Optional.of(session("sid-version", 50L, UserStatus.active)));
		when(sessionStore.findBySessionId("sid-role"))
			.thenReturn(Optional.of(session("sid-role", 51L, UserStatus.active)));
		when(sessionStore.findBySessionId("sid-status"))
			.thenReturn(Optional.of(session("sid-status", 52L, UserStatus.active)));
		when(sessionStore.findBySessionId("sid-valid"))
			.thenReturn(Optional.of(session("sid-valid", 53L, UserStatus.active)));
		when(userRepository.findAuthStateById(50L))
			.thenReturn(Optional.of(canonical(50L, UserRole.user, UserStatus.active, 1L)));
		when(userRepository.findAuthStateById(51L))
			.thenReturn(Optional.of(canonical(51L, UserRole.admin, UserStatus.active, 0L)));
		when(userRepository.findAuthStateById(52L))
			.thenReturn(Optional.of(canonical(52L, UserRole.user, UserStatus.suspended, 0L)));
		when(userRepository.findAuthStateById(53L))
			.thenReturn(Optional.of(canonical(53L, UserRole.user, UserStatus.active, 0L)));

		scheduler.runHeartbeat();

		verify(registry).closeSession("sid-version");
		verify(registry).closeSession("sid-role");
		verify(registry).closeSession("sid-status");
		verify(registry, never()).closeSession("sid-valid");
	}

	@Test
	void redisAndDatabaseFailuresCloseOnlyTheirSessionsAndContinueWithoutLoggingSecrets() {
		SseSessionConnection redisFailure = new SseSessionConnection(60L, "sid-redis-secret");
		SseSessionConnection databaseFailure = new SseSessionConnection(61L, "sid-db-secret");
		SseSessionConnection valid = new SseSessionConnection(62L, "sid-valid");
		when(registry.activeSessionsInShard(0, 4))
			.thenReturn(List.of(redisFailure, databaseFailure, valid));
		when(sessionStore.findBySessionId("sid-redis-secret"))
			.thenThrow(new IllegalStateException("redis failed for sid-redis-secret"));
		when(sessionStore.findBySessionId("sid-db-secret"))
			.thenReturn(Optional.of(session("sid-db-secret", 61L, UserStatus.active)));
		when(sessionStore.findBySessionId("sid-valid"))
			.thenReturn(Optional.of(session("sid-valid", 62L, UserStatus.active)));
		when(userRepository.findAuthStateById(61L))
			.thenThrow(new IllegalStateException("database failed for sid-db-secret"));
		when(userRepository.findAuthStateById(62L))
			.thenReturn(Optional.of(canonical(62L, UserRole.user, UserStatus.active, 0L)));
		Logger logger = (Logger) LoggerFactory.getLogger(SseHeartbeatScheduler.class);
		ListAppender<ILoggingEvent> logs = new ListAppender<>();
		logs.start();
		logger.addAppender(logs);

		try {
			scheduler.runHeartbeat();
		} finally {
			logger.detachAppender(logs);
			logs.stop();
		}

		verify(registry).closeSession("sid-redis-secret");
		verify(registry).closeSession("sid-db-secret");
		verify(registry, never()).closeSession("sid-valid");
		verify(userRepository).findAuthStateById(62L);
		assertThat(logs.list).hasSize(2);
		assertThat(logs.list)
			.allMatch(event -> event.getThrowableProxy() == null)
			.extracting(ILoggingEvent::getFormattedMessage)
			.noneMatch(message -> message.contains("sid-redis-secret") || message.contains("sid-db-secret"));
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
			"user%d@example.com".formatted(userId),
			"refresh-token-hash",
			null,
			UserRole.user,
			status,
			OffsetDateTime.parse("2026-07-10T12:00:00+09:00"),
			0L
		);
	}

	private static UserAuthState canonical(
		Long userId,
		UserRole role,
		UserStatus status,
		long authVersion
	) {
		return new UserAuthState(
			"user%d@example.com".formatted(userId),
			role,
			status,
			authVersion
		);
	}
}
