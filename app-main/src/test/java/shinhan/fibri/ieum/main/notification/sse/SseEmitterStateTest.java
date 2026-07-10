package shinhan.fibri.ieum.main.notification.sse;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import shinhan.fibri.ieum.main.notification.domain.NotificationType;

class SseEmitterStateTest {

	@Test
	void drainsDurableBeforeEphemeralAndHeartbeat() {
		ManualExecutor executor = new ManualExecutor();
		List<OutboundEvent> sent = new ArrayList<>();
		SseEmitterState state = state(2, executor, sent::add, () -> { });

		state.enqueue(ephemeral("old"));
		state.enqueue(OutboundEvent.heartbeat());
		state.enqueue(durable(1L));
		executor.runAll();

		assertThat(sent).extracting(OutboundEvent::kind).containsExactly(
			OutboundEvent.Kind.durable,
			OutboundEvent.Kind.ephemeral,
			OutboundEvent.Kind.heartbeat
		);
	}

	@Test
	void closesAndRequiresReconcileWhenDurableQueueOverflows() {
		ManualExecutor executor = new ManualExecutor();
		List<OutboundEvent> sent = new ArrayList<>();
		AtomicInteger closes = new AtomicInteger();
		SseEmitterState state = state(1, executor, sent::add, closes::incrementAndGet);

		state.enqueue(durable(1L));
		state.enqueue(ephemeral("pending"));
		state.enqueue(OutboundEvent.heartbeat());
		state.enqueue(durable(2L));
		executor.runAll();

		assertThat(state.isClosed()).isTrue();
		assertThat(state.reconcileRequired()).isTrue();
		assertThat(state.droppedCount()).isEqualTo(4L);
		assertThat(closes).hasValue(1);
		assertThat(sent).isEmpty();
	}

	@Test
	void keepsOnlyLatestEphemeralEvent() {
		ManualExecutor executor = new ManualExecutor();
		List<OutboundEvent> sent = new ArrayList<>();
		SseEmitterState state = state(2, executor, sent::add, () -> { });

		state.enqueue(ephemeral("old"));
		state.enqueue(ephemeral("new"));
		executor.runAll();

		assertThat(sent).hasSize(1);
		assertThat(sent.getFirst().payload().title()).isEqualTo("new");
		assertThat(state.droppedCount()).isEqualTo(1L);
	}

	@Test
	void shedsEphemeralEventsWhenExecutorRejectsButKeepsConnectionUsable() {
		SwitchingExecutor executor = new SwitchingExecutor(false);
		List<OutboundEvent> sent = new ArrayList<>();
		AtomicInteger closes = new AtomicInteger();
		SseEmitterState state = state(2, executor, sent::add, closes::incrementAndGet);

		state.enqueue(ephemeral("dropped"));
		executor.accepting = true;
		state.enqueue(ephemeral("delivered"));
		executor.runAll();

		assertThat(state.isClosed()).isFalse();
		assertThat(state.droppedCount()).isEqualTo(1L);
		assertThat(closes).hasValue(0);
		assertThat(sent).extracting(event -> event.payload().title()).containsExactly("delivered");
	}

	@Test
	void closesWhenExecutorRejectsDurableEvent() {
		SwitchingExecutor executor = new SwitchingExecutor(false);
		AtomicInteger closes = new AtomicInteger();
		SseEmitterState state = state(2, executor, event -> { }, closes::incrementAndGet);

		state.enqueue(durable(1L));

		assertThat(state.isClosed()).isTrue();
		assertThat(state.reconcileRequired()).isTrue();
		assertThat(state.droppedCount()).isEqualTo(1L);
		assertThat(closes).hasValue(1);
	}

	@Test
	void closesAfterSendFailureAndIgnoresLaterEvents() {
		AtomicInteger closes = new AtomicInteger();
		SseEmitterState state = state(
			2,
			Runnable::run,
			event -> { throw new IllegalStateException("send failed"); },
			closes::incrementAndGet
		);

		state.enqueue(durable(1L));
		state.enqueue(durable(2L));

		assertThat(state.isClosed()).isTrue();
		assertThat(state.reconcileRequired()).isTrue();
		assertThat(closes).hasValue(1);
	}

	@Test
	void drainsEventEnqueuedDuringSendWithoutSubmittingSecondTask() throws Exception {
		ManualExecutor executor = new ManualExecutor();
		List<OutboundEvent> sent = new ArrayList<>();
		CountDownLatch firstSendStarted = new CountDownLatch(1);
		CountDownLatch releaseFirstSend = new CountDownLatch(1);
		SseEmitterState state = state(2, executor, event -> {
			sent.add(event);
			if (event.payload().notificationId().equals(1L)) {
				firstSendStarted.countDown();
				await(releaseFirstSend);
			}
		}, () -> { });
		state.enqueue(durable(1L));

		Thread worker = new Thread(executor::runNext);
		worker.start();
		assertThat(firstSendStarted.await(2, TimeUnit.SECONDS)).isTrue();
		state.enqueue(durable(2L));
		releaseFirstSend.countDown();
		worker.join(2_000L);

		assertThat(worker.isAlive()).isFalse();
		assertThat(sent).extracting(event -> event.payload().notificationId()).containsExactly(1L, 2L);
		assertThat(executor.submissionCount()).isEqualTo(1);
	}

	private static SseEmitterState state(
		int durableQueueCapacity,
		Executor executor,
		Consumer<OutboundEvent> sender,
		Runnable closeAction
	) {
		return new SseEmitterState(durableQueueCapacity, executor, sender, closeAction);
	}

	private static OutboundEvent durable(Long id) {
		return OutboundEvent.durable(NotificationSsePayload.durable(
			id,
			NotificationType.question,
			"durable-" + id,
			null,
			id,
			OffsetDateTime.parse("2026-07-10T12:00:00+09:00")
		));
	}

	private static OutboundEvent ephemeral(String title) {
		return OutboundEvent.ephemeral(NotificationSsePayload.ephemeral(
			NotificationType.location,
			title,
			null,
			1L,
			OffsetDateTime.parse("2026-07-10T12:00:00+09:00")
		));
	}

	private static void await(CountDownLatch latch) {
		try {
			if (!latch.await(2, TimeUnit.SECONDS)) {
				throw new AssertionError("Timed out waiting for sender release");
			}
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new AssertionError(exception);
		}
	}

	private static class ManualExecutor implements Executor {

		private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
		private int submissionCount;

		@Override
		public synchronized void execute(Runnable command) {
			tasks.addLast(command);
			submissionCount++;
		}

		synchronized void runAll() {
			while (!tasks.isEmpty()) {
				tasks.removeFirst().run();
			}
		}

		synchronized void runNext() {
			tasks.removeFirst().run();
		}

		synchronized int submissionCount() {
			return submissionCount;
		}
	}

	private static final class SwitchingExecutor extends ManualExecutor {

		private boolean accepting;

		private SwitchingExecutor(boolean accepting) {
			this.accepting = accepting;
		}

		@Override
		public synchronized void execute(Runnable command) {
			if (!accepting) {
				throw new RejectedExecutionException("rejected for test");
			}
			super.execute(command);
		}
	}
}
