package shinhan.fibri.ieum.main.notification.sse;

import java.util.ArrayDeque;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

final class SseEmitterState {

	private final Object stateLock = new Object();
	private final int durableQueueCapacity;
	private final Executor executor;
	private final Consumer<OutboundEvent> sender;
	private final Runnable closeAction;
	private final ArrayDeque<OutboundEvent> durableQueue = new ArrayDeque<>();

	private OutboundEvent pendingEphemeral;
	private boolean heartbeatPending;
	private boolean inFlight;
	private boolean closed;
	private boolean reconcileRequired;
	private boolean closeNotified;
	private long droppedCount;

	SseEmitterState(
		int durableQueueCapacity,
		Executor executor,
		Consumer<OutboundEvent> sender,
		Runnable closeAction
	) {
		if (durableQueueCapacity < 1) {
			throw new IllegalArgumentException("durableQueueCapacity must be positive");
		}
		this.durableQueueCapacity = durableQueueCapacity;
		this.executor = Objects.requireNonNull(executor, "executor must not be null");
		this.sender = Objects.requireNonNull(sender, "sender must not be null");
		this.closeAction = Objects.requireNonNull(closeAction, "closeAction must not be null");
	}

	void enqueue(OutboundEvent event) {
		Objects.requireNonNull(event, "event must not be null");
		boolean scheduleDrain = false;
		boolean notifyClose = false;
		synchronized (stateLock) {
			if (closed) {
				return;
			}
			if (event.kind() == OutboundEvent.Kind.durable && durableQueue.size() == durableQueueCapacity) {
				droppedCount += pendingEventCount() + 1;
				clearPendingEvents();
				closed = true;
				reconcileRequired = true;
				notifyClose = markCloseNotified();
			} else {
				enqueuePendingEvent(event);
				if (!inFlight) {
					inFlight = true;
					scheduleDrain = true;
				}
			}
		}
		if (notifyClose) {
			closeAction.run();
			return;
		}
		if (scheduleDrain) {
			submitDrain();
		}
	}

	void close() {
		boolean notifyClose;
		synchronized (stateLock) {
			if (closed) {
				return;
			}
			droppedCount += pendingEventCount();
			clearPendingEvents();
			closed = true;
			inFlight = false;
			notifyClose = markCloseNotified();
		}
		if (notifyClose) {
			closeAction.run();
		}
	}

	boolean isClosed() {
		synchronized (stateLock) {
			return closed;
		}
	}

	boolean reconcileRequired() {
		synchronized (stateLock) {
			return reconcileRequired;
		}
	}

	long droppedCount() {
		synchronized (stateLock) {
			return droppedCount;
		}
	}

	private void enqueuePendingEvent(OutboundEvent event) {
		switch (event.kind()) {
			case durable -> durableQueue.addLast(event);
			case ephemeral -> {
				if (pendingEphemeral != null) {
					droppedCount++;
				}
				pendingEphemeral = event;
			}
			case heartbeat -> heartbeatPending = true;
		}
	}

	private void submitDrain() {
		try {
			executor.execute(this::drain);
		} catch (RejectedExecutionException exception) {
			handleRejectedExecution();
		}
	}

	private void handleRejectedExecution() {
		boolean notifyClose = false;
		synchronized (stateLock) {
			if (closed) {
				inFlight = false;
				return;
			}
			boolean durableLost = !durableQueue.isEmpty();
			droppedCount += pendingEventCount();
			clearPendingEvents();
			inFlight = false;
			if (durableLost) {
				closed = true;
				reconcileRequired = true;
				notifyClose = markCloseNotified();
			}
		}
		if (notifyClose) {
			closeAction.run();
		}
	}

	private void drain() {
		while (true) {
			OutboundEvent event;
			synchronized (stateLock) {
				if (closed) {
					inFlight = false;
					return;
				}
				event = nextEvent();
				if (event == null) {
					inFlight = false;
					return;
				}
			}

			try {
				sender.accept(event);
			} catch (RuntimeException exception) {
				closeAfterSendFailure(event);
				return;
			}
		}
	}

	private OutboundEvent nextEvent() {
		OutboundEvent durable = durableQueue.pollFirst();
		if (durable != null) {
			return durable;
		}
		if (pendingEphemeral != null) {
			OutboundEvent ephemeral = pendingEphemeral;
			pendingEphemeral = null;
			return ephemeral;
		}
		if (heartbeatPending) {
			heartbeatPending = false;
			return OutboundEvent.heartbeat();
		}
		return null;
	}

	private void closeAfterSendFailure(OutboundEvent failedEvent) {
		boolean notifyClose;
		synchronized (stateLock) {
			if (closed) {
				return;
			}
			droppedCount += pendingEventCount();
			clearPendingEvents();
			closed = true;
			inFlight = false;
			if (failedEvent.kind() == OutboundEvent.Kind.durable) {
				reconcileRequired = true;
			}
			notifyClose = markCloseNotified();
		}
		if (notifyClose) {
			closeAction.run();
		}
	}

	private int pendingEventCount() {
		return durableQueue.size() + (pendingEphemeral == null ? 0 : 1) + (heartbeatPending ? 1 : 0);
	}

	private void clearPendingEvents() {
		durableQueue.clear();
		pendingEphemeral = null;
		heartbeatPending = false;
	}

	private boolean markCloseNotified() {
		if (closeNotified) {
			return false;
		}
		closeNotified = true;
		return true;
	}
}
