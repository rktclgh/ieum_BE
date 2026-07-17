package shinhan.fibri.ieum.main.notification.sse;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import shinhan.fibri.ieum.main.notification.presence.PresenceRegistry;

@Component
public class SseConnectionRegistry {

	private static final int SHUTDOWN_BATCH_SIZE = 50;
	private static final long SHUTDOWN_BATCH_DELAY_MILLIS = 250L;

	private final NotificationProperties properties;
	private final Executor executor;
	private final PresenceRegistry presenceRegistry;
	private final ConcurrentHashMap<Long, UserConnections> connectionsByUser = new ConcurrentHashMap<>();
	private final AtomicBoolean acceptingConnections = new AtomicBoolean(true);
	private final Object lifecycleMonitor = new Object();

	public SseConnectionRegistry(
		NotificationProperties properties,
		@Qualifier("notificationTaskExecutor") Executor executor,
		PresenceRegistry presenceRegistry
	) {
		this.properties = Objects.requireNonNull(properties, "properties must not be null");
		this.executor = Objects.requireNonNull(executor, "executor must not be null");
		this.presenceRegistry = Objects.requireNonNull(presenceRegistry, "presenceRegistry must not be null");
	}

	public boolean register(Long userId, String sessionId, SseEmitter emitter) {
		return register(userId, sessionId, new SpringSseEmitterConnection(emitter));
	}

	boolean register(Long userId, String sessionId, SseEmitterConnection emitter) {
		Objects.requireNonNull(userId, "userId must not be null");
		Objects.requireNonNull(sessionId, "sessionId must not be null");
		Objects.requireNonNull(emitter, "emitter must not be null");
		Connection connection = new Connection(userId, sessionId, emitter);
		SseEmitterState state = new SseEmitterState(
			properties.durableQueuePerEmitter(),
			executor,
			event -> send(connection, event),
			() -> closeAndRemove(connection)
		);
		connection.setState(state);
		emitter.onCompletion(() -> remove(connection));
		emitter.onTimeout(() -> remove(connection));
		emitter.onError(error -> remove(connection));

		AtomicReference<Connection> evictedReference = new AtomicReference<>();
		synchronized (lifecycleMonitor) {
			if (!acceptingConnections.get()) {
				emitter.complete();
				return false;
			}
			connectionsByUser.compute(userId, (ignored, existing) -> {
				UserConnections connections = existing == null ? new UserConnections() : existing;
				evictedReference.set(connections.add(connection, properties.maxConnectionsPerUser()));
				return connections;
			});
		}
		Connection evicted = evictedReference.get();
		if (evicted != null) {
			evicted.close();
		}
		return true;
	}

	public void push(Long userId, OutboundEvent event) {
		UserConnections connections = connectionsByUser.get(userId);
		if (connections == null) {
			return;
		}
		for (Connection connection : connections.snapshot()) {
			connection.enqueue(event);
		}
	}

	void enqueueHeartbeat() {
		for (UserConnections connections : connectionsByUser.values()) {
			for (Connection connection : connections.snapshot()) {
				connection.enqueue(OutboundEvent.heartbeat());
			}
		}
	}

	List<SseSessionConnection> activeSessionsInShard(int shard, int shardCount) {
		if (shardCount < 1 || shard < 0 || shard >= shardCount) {
			throw new IllegalArgumentException("invalid session shard");
		}

		Map<String, SseSessionConnection> sessionsById = new LinkedHashMap<>();
		for (UserConnections connections : connectionsByUser.values()) {
			for (Connection connection : connections.snapshot()) {
				if (Math.floorMod(connection.sessionId.hashCode(), shardCount) == shard) {
					sessionsById.putIfAbsent(
						connection.sessionId,
						new SseSessionConnection(connection.userId, connection.sessionId)
					);
				}
			}
		}
		return List.copyOf(sessionsById.values());
	}

	public void closeSession(String sessionId) {
		for (UserConnections connections : connectionsByUser.values()) {
			for (Connection connection : connections.snapshot()) {
				if (connection.sessionId.equals(sessionId)) {
					connection.close();
				}
			}
		}
	}

	public void closeEmitter(Long userId, String sessionId, SseEmitter emitter) {
		UserConnections connections = connectionsByUser.get(userId);
		if (connections == null) {
			return;
		}
		for (Connection connection : connections.snapshot()) {
			if (connection.matches(sessionId, emitter)) {
				connection.close();
			}
		}
	}

	public void closeUser(Long userId) {
		UserConnections connections = connectionsByUser.get(userId);
		if (connections == null) {
			return;
		}
		for (Connection connection : connections.snapshot()) {
			connection.close();
		}
	}

	public boolean isOnline(Long userId) {
		UserConnections connections = connectionsByUser.get(userId);
		return connections != null && !connections.isEmpty();
	}

	public Set<Long> onlineUserIds() {
		return connectionsByUser.entrySet().stream()
			.filter(entry -> !entry.getValue().isEmpty())
			.map(java.util.Map.Entry::getKey)
			.collect(java.util.stream.Collectors.toUnmodifiableSet());
	}

	@PreDestroy
	public void shutdown() {
		List<Connection> connections;
		synchronized (lifecycleMonitor) {
			acceptingConnections.set(false);
			connections = connectionsByUser.values().stream()
				.flatMap(userConnections -> userConnections.snapshot().stream())
				.toList();
		}
		for (int index = 0; index < connections.size(); index++) {
			connections.get(index).close();
			if ((index + 1) % SHUTDOWN_BATCH_SIZE == 0 && index + 1 < connections.size()) {
				pauseBeforeNextShutdownBatch();
			}
		}
	}

	private void pauseBeforeNextShutdownBatch() {
		try {
			TimeUnit.MILLISECONDS.sleep(SHUTDOWN_BATCH_DELAY_MILLIS);
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
		}
	}

	int connectionCount(Long userId) {
		UserConnections connections = connectionsByUser.get(userId);
		return connections == null ? 0 : connections.size();
	}

	private void closeAndRemove(Connection connection) {
		try {
			if (connection.shouldCompleteOnClose()) {
				connection.emitter.complete();
			}
		} finally {
			remove(connection);
		}
	}

	private void remove(Connection connection) {
		AtomicBoolean lastConnectionRemoved = new AtomicBoolean();
		connectionsByUser.computeIfPresent(connection.userId, (ignored, connections) -> {
			connections.remove(connection);
			if (connections.isEmpty()) {
				lastConnectionRemoved.set(true);
				return null;
			}
			return connections;
		});
		if (lastConnectionRemoved.get()) {
			presenceRegistry.removeOnLastDisconnect(connection.userId);
		}
	}

	private void send(Connection connection, OutboundEvent event) {
		try {
			connection.emitter.send(event);
		} catch (IOException exception) {
			connection.skipCompletionOnClose();
			throw new UncheckedIOException(exception);
		}
	}

	private final class Connection {

		private final Long userId;
		private final String sessionId;
		private final SseEmitterConnection emitter;
		private final AtomicBoolean completeOnClose = new AtomicBoolean(true);
		private SseEmitterState state;

		private Connection(Long userId, String sessionId, SseEmitterConnection emitter) {
			this.userId = userId;
			this.sessionId = sessionId;
			this.emitter = emitter;
		}

		private void setState(SseEmitterState state) {
			this.state = state;
		}

		private void enqueue(OutboundEvent event) {
			state.enqueue(event);
		}

		private void close() {
			state.close();
		}

		private boolean shouldCompleteOnClose() {
			return completeOnClose.get();
		}

		private void skipCompletionOnClose() {
			completeOnClose.set(false);
		}

		private boolean matches(String sessionId, SseEmitter target) {
			return this.sessionId.equals(sessionId)
				&& emitter instanceof SpringSseEmitterConnection springEmitter
				&& springEmitter.wraps(target);
		}
	}

	private static final class UserConnections {

		private final List<Connection> connections = new ArrayList<>();

		synchronized Connection add(Connection connection, int maxConnections) {
			Connection evicted = connections.size() == maxConnections ? connections.removeFirst() : null;
			connections.add(connection);
			return evicted;
		}

		synchronized boolean remove(Connection connection) {
			return connections.remove(connection);
		}

		synchronized boolean isEmpty() {
			return connections.isEmpty();
		}

		synchronized int size() {
			return connections.size();
		}

		synchronized List<Connection> snapshot() {
			return List.copyOf(connections);
		}
	}
}
