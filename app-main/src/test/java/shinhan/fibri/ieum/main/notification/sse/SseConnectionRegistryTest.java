package shinhan.fibri.ieum.main.notification.sse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import shinhan.fibri.ieum.main.notification.domain.NotificationType;
import shinhan.fibri.ieum.main.notification.presence.PresenceRegistry;

class SseConnectionRegistryTest {

	@Test
	void pushesEventsToAllConnectionsOfUser() {
		SseConnectionRegistry registry = registry(5);
		FakeConnection first = new FakeConnection();
		FakeConnection second = new FakeConnection();
		registry.register(42L, "sid-1", first);
		registry.register(42L, "sid-2", second);

		registry.push(42L, durable(1L));

		assertThat(first.sent).extracting(OutboundEvent::kind).containsExactly(OutboundEvent.Kind.durable);
		assertThat(second.sent).extracting(OutboundEvent::kind).containsExactly(OutboundEvent.Kind.durable);
		assertThat(registry.isOnline(42L)).isTrue();
		assertThat(registry.onlineUserIds()).containsExactly(42L);
	}

	@Test
	void evictsOldestConnectionWhenUserConnectionLimitIsExceeded() {
		SseConnectionRegistry registry = registry(2);
		FakeConnection oldest = new FakeConnection();
		FakeConnection middle = new FakeConnection();
		FakeConnection newest = new FakeConnection();
		registry.register(42L, "sid-1", oldest);
		registry.register(42L, "sid-2", middle);
		registry.register(42L, "sid-3", newest);

		registry.push(42L, durable(1L));

		assertThat(oldest.completeCount).isEqualTo(1);
		assertThat(oldest.sent).isEmpty();
		assertThat(middle.sent).hasSize(1);
		assertThat(newest.sent).hasSize(1);
		assertThat(registry.connectionCount(42L)).isEqualTo(2);
	}

	@Test
	void closesOnlyConnectionsForRequestedSession() {
		SseConnectionRegistry registry = registry(5);
		FakeConnection target = new FakeConnection();
		FakeConnection other = new FakeConnection();
		registry.register(42L, "sid-target", target);
		registry.register(42L, "sid-other", other);

		registry.closeSession("sid-target");
		registry.push(42L, durable(1L));

		assertThat(target.completeCount).isEqualTo(1);
		assertThat(target.sent).isEmpty();
		assertThat(other.completeCount).isZero();
		assertThat(other.sent).hasSize(1);
	}

	@Test
	void closesAllConnectionsForUser() {
		SseConnectionRegistry registry = registry(5);
		FakeConnection first = new FakeConnection();
		FakeConnection second = new FakeConnection();
		FakeConnection differentUser = new FakeConnection();
		registry.register(42L, "sid-1", first);
		registry.register(42L, "sid-2", second);
		registry.register(99L, "sid-3", differentUser);

		registry.closeUser(42L);

		assertThat(first.completeCount).isEqualTo(1);
		assertThat(second.completeCount).isEqualTo(1);
		assertThat(differentUser.completeCount).isZero();
		assertThat(registry.isOnline(42L)).isFalse();
		assertThat(registry.isOnline(99L)).isTrue();
	}

	@Test
	void removesConnectionWhenEmitterCompletesOrErrors() {
		SseConnectionRegistry registry = registry(5);
		FakeConnection completed = new FakeConnection();
		FakeConnection errored = new FakeConnection();
		registry.register(42L, "sid-1", completed);
		registry.register(42L, "sid-2", errored);

		completed.fireCompletion();
		errored.fireError(new IllegalStateException("connection failed"));

		assertThat(registry.isOnline(42L)).isFalse();
		assertThat(registry.onlineUserIds()).isEmpty();
	}

	@Test
	void completesTimedOutEmitterBeforeRemovingConnection() {
		SseConnectionRegistry registry = registry(5);
		FakeConnection timedOut = new FakeConnection();
		registry.register(42L, "sid-timeout", timedOut);

		timedOut.fireTimeout();
		timedOut.fireTimeout();

		assertThat(timedOut.completeCount).isEqualTo(1);
		assertThat(registry.connectionCount(42L)).isZero();
		assertThat(registry.isOnline(42L)).isFalse();
	}

	@Test
	void removesPresenceWhenUsersLastConnectionEnds() {
		PresenceRegistry presenceRegistry = org.mockito.Mockito.mock(PresenceRegistry.class);
		SseConnectionRegistry registry = registry(5, presenceRegistry);
		FakeConnection connection = new FakeConnection();
		registry.register(42L, "sid-1", connection);

		connection.fireCompletion();

		org.mockito.Mockito.verify(presenceRegistry).removeOnLastDisconnect(42L);
	}

	@Test
	void removesConnectionAndPresenceEvenWhenEmitterCompletionThrows() {
		PresenceRegistry presenceRegistry = org.mockito.Mockito.mock(PresenceRegistry.class);
		SseConnectionRegistry registry = registry(5, presenceRegistry);
		FakeConnection connection = new FakeConnection(true);
		registry.register(42L, "sid-throwing-complete", connection);

		assertThatThrownBy(() -> registry.closeSession("sid-throwing-complete"))
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("emitter completion failed");

		assertThat(registry.connectionCount(42L)).isZero();
		assertThat(registry.isOnline(42L)).isFalse();
		assertThat(registry.onlineUserIds()).isEmpty();
		org.mockito.Mockito.verify(presenceRegistry).removeOnLastDisconnect(42L);
	}

	@Test
	void sendFailureRemovesConnectionWithoutCompletingUnusableEmitter() {
		PresenceRegistry presenceRegistry = org.mockito.Mockito.mock(PresenceRegistry.class);
		SseConnectionRegistry registry = registry(5, presenceRegistry);
		FakeConnection connection = FakeConnection.throwingOnSend();
		registry.register(42L, "sid-failed-send", connection);

		registry.push(42L, durable(1L));

		assertThat(connection.completeCount).isZero();
		assertThat(registry.connectionCount(42L)).isZero();
		assertThat(registry.isOnline(42L)).isFalse();
		assertThat(registry.onlineUserIds()).isEmpty();
		org.mockito.Mockito.verify(presenceRegistry).removeOnLastDisconnect(42L);
	}

	@Test
	void shutdownClosesAllConnections() {
		SseConnectionRegistry registry = registry(5);
		FakeConnection first = new FakeConnection();
		FakeConnection second = new FakeConnection();
		registry.register(42L, "sid-1", first);
		registry.register(99L, "sid-2", second);

		registry.shutdown();

		assertThat(first.completeCount).isEqualTo(1);
		assertThat(second.completeCount).isEqualTo(1);
		assertThat(registry.onlineUserIds()).isEmpty();
	}

	@Test
	void shutdownRejectsNewConnections() {
		SseConnectionRegistry registry = registry(5);
		registry.shutdown();
		FakeConnection connection = new FakeConnection();

		registry.register(42L, "sid-1", connection);

		assertThat(connection.completeCount).isEqualTo(1);
		assertThat(registry.onlineUserIds()).isEmpty();
	}

	@Test
	void enqueuesHeartbeatForEveryActiveConnection() {
		SseConnectionRegistry registry = registry(5);
		FakeConnection first = new FakeConnection();
		FakeConnection second = new FakeConnection();
		registry.register(42L, "sid-1", first);
		registry.register(99L, "sid-2", second);

		registry.enqueueHeartbeat();

		assertThat(first.sent).extracting(OutboundEvent::kind).containsExactly(OutboundEvent.Kind.heartbeat);
		assertThat(second.sent).extracting(OutboundEvent::kind).containsExactly(OutboundEvent.Kind.heartbeat);
	}

	@Test
	void returnsOnlyConnectionsAssignedToRequestedSessionShard() {
		SseConnectionRegistry registry = registry(5);
		String firstShardZero = sessionIdForShard(0, 4, 1);
		String secondShardZero = sessionIdForShard(0, 4, 2);
		String shardOne = sessionIdForShard(1, 4, 1);
		registry.register(42L, firstShardZero, new FakeConnection());
		registry.register(99L, secondShardZero, new FakeConnection());
		registry.register(77L, shardOne, new FakeConnection());

		assertThat(registry.activeSessionsInShard(0, 4))
			.containsExactlyInAnyOrder(
				new SseSessionConnection(42L, firstShardZero),
				new SseSessionConnection(99L, secondShardZero)
			);
	}

	@Test
	void returnsSameSessionOnlyOnceWhenItHasMultipleConnections() {
		SseConnectionRegistry registry = registry(5);
		String sessionId = sessionIdForShard(0, 4, 1);
		registry.register(42L, sessionId, new FakeConnection());
		registry.register(42L, sessionId, new FakeConnection());

		assertThat(registry.activeSessionsInShard(0, 4))
			.containsExactly(new SseSessionConnection(42L, sessionId));
	}

	private static SseConnectionRegistry registry(int maxConnectionsPerUser) {
		return registry(maxConnectionsPerUser, org.mockito.Mockito.mock(PresenceRegistry.class));
	}

	private static SseConnectionRegistry registry(int maxConnectionsPerUser, PresenceRegistry presenceRegistry) {
		NotificationProperties properties = new NotificationProperties(
			1_800_000L,
			maxConnectionsPerUser,
			32,
			15_000L,
			4,
			3_000L,
			8_000L,
			4,
			16,
			500
		);
		return new SseConnectionRegistry(properties, Runnable::run, presenceRegistry);
	}

	private static OutboundEvent durable(Long id) {
		return OutboundEvent.durable(NotificationSsePayload.durable(
			id,
			NotificationType.question,
			"새 답변",
			null,
			id,
			OffsetDateTime.parse("2026-07-10T12:00:00+09:00")
		));
	}

	private static String sessionIdForShard(int shard, int shardCount, int occurrence) {
		int found = 0;
		for (int candidate = 0; ; candidate++) {
			String sessionId = "sid-" + candidate;
			if (Math.floorMod(sessionId.hashCode(), shardCount) == shard && ++found == occurrence) {
				return sessionId;
			}
		}
	}

	private static final class FakeConnection implements SseEmitterConnection {

		private final List<OutboundEvent> sent = new ArrayList<>();
		private final boolean throwOnComplete;
		private final boolean throwOnSend;
		private Runnable completionCallback;
		private Runnable timeoutCallback;
		private Consumer<Throwable> errorCallback;
		private int completeCount;

		private FakeConnection() {
			this(false);
		}

		private FakeConnection(boolean throwOnComplete) {
			this(throwOnComplete, false);
		}

		private FakeConnection(boolean throwOnComplete, boolean throwOnSend) {
			this.throwOnComplete = throwOnComplete;
			this.throwOnSend = throwOnSend;
		}

		private static FakeConnection throwingOnSend() {
			return new FakeConnection(false, true);
		}

		@Override
		public void send(OutboundEvent event) throws IOException {
			if (throwOnSend) {
				throw new IOException("emitter send failed");
			}
			sent.add(event);
		}

		@Override
		public void complete() {
			completeCount++;
			if (throwOnComplete) {
				throw new IllegalStateException("emitter completion failed");
			}
		}

		@Override
		public void onCompletion(Runnable callback) {
			completionCallback = callback;
		}

		@Override
		public void onTimeout(Runnable callback) {
			timeoutCallback = callback;
		}

		@Override
		public void onError(Consumer<Throwable> callback) {
			errorCallback = callback;
		}

		void fireCompletion() {
			completionCallback.run();
		}

		void fireTimeout() {
			timeoutCallback.run();
		}

		void fireError(Throwable error) {
			errorCallback.accept(error);
		}
	}
}
