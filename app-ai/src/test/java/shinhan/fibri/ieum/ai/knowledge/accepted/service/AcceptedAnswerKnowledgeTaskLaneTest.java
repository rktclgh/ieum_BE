package shinhan.fibri.ieum.ai.knowledge.accepted.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import shinhan.fibri.ieum.ai.config.AcceptedAnswerKnowledgeConfiguration;

class AcceptedAnswerKnowledgeTaskLaneTest {

	@Test
	void runsExactAnswerIdsSequentiallyWithOneWorkerAndThirtyTwoQueueSlots() throws Exception {
		ThreadPoolTaskExecutor executor =
			new AcceptedAnswerKnowledgeConfiguration().acceptedAnswerKnowledgeTaskExecutor();
		CountDownLatch firstStarted = new CountDownLatch(1);
		CountDownLatch releaseFirst = new CountDownLatch(1);
		CountDownLatch queuedProcessed = new CountDownLatch(32);
		List<Long> processed = new CopyOnWriteArrayList<>();
		AtomicReference<String> workerThread = new AtomicReference<>();
		AcceptedAnswerKnowledgeTaskLane lane = new AcceptedAnswerKnowledgeTaskLane(
			true,
			executor,
			answerId -> {
				processed.add(answerId);
				workerThread.compareAndSet(null, Thread.currentThread().getName());
				if (answerId == 1L) {
					firstStarted.countDown();
					await(releaseFirst);
				}
				else {
					queuedProcessed.countDown();
				}
			}
		);

		try {
			assertThat(lane.submit(1L)).isEqualTo(AcceptedAnswerKnowledgeTaskSubmission.ENQUEUED);
			assertThat(firstStarted.await(5, TimeUnit.SECONDS)).isTrue();
			assertThat(lane.submit(1L))
				.isEqualTo(AcceptedAnswerKnowledgeTaskSubmission.ALREADY_ACTIVE);
			for (long answerId = 2; answerId <= 33; answerId++) {
				assertThat(lane.submit(answerId))
					.isEqualTo(AcceptedAnswerKnowledgeTaskSubmission.ENQUEUED);
			}

			assertThat(lane.submit(2L))
				.isEqualTo(AcceptedAnswerKnowledgeTaskSubmission.ALREADY_ACTIVE);
			assertThat(lane.submit(34L))
				.isEqualTo(AcceptedAnswerKnowledgeTaskSubmission.SATURATED);
			assertThat(processed).doesNotContain(34L);
			assertThat(workerThread.get()).startsWith("ieum-accepted-answer-");
			assertThat(workerThread.get()).isNotEqualTo(Thread.currentThread().getName());
		}
		finally {
			releaseFirst.countDown();
			assertThat(queuedProcessed.await(5, TimeUnit.SECONDS)).isTrue();
			executor.shutdown();
		}

		List<Long> expected = new ArrayList<>();
		for (long answerId = 1; answerId <= 33; answerId++) {
			expected.add(answerId);
		}
		assertThat(processed).containsExactlyElementsOf(expected);
	}

	@Test
	void disabledLaneAndRejectedSubmissionPerformNoWorkAndClearDedupState() {
		List<Long> processed = new CopyOnWriteArrayList<>();
		AcceptedAnswerKnowledgeTaskLane disabled = new AcceptedAnswerKnowledgeTaskLane(
			false,
			Runnable::run,
			processed::add
		);

		assertThat(disabled.submit(42L))
			.isEqualTo(AcceptedAnswerKnowledgeTaskSubmission.DISABLED);
		assertThat(processed).isEmpty();

		AtomicInteger submissions = new AtomicInteger();
		AcceptedAnswerKnowledgeTaskLane saturated = new AcceptedAnswerKnowledgeTaskLane(
			true,
			command -> {
				submissions.incrementAndGet();
				throw new java.util.concurrent.RejectedExecutionException("full");
			},
			processed::add
		);

		assertThat(saturated.submit(42L))
			.isEqualTo(AcceptedAnswerKnowledgeTaskSubmission.SATURATED);
		assertThat(saturated.submit(42L))
			.isEqualTo(AcceptedAnswerKnowledgeTaskSubmission.SATURATED);
		assertThat(submissions).hasValue(2);
		assertThat(processed).isEmpty();
	}

	@Test
	void rejectsInvalidAnswerIdBeforeSubmitting() {
		AcceptedAnswerKnowledgeTaskLane lane = new AcceptedAnswerKnowledgeTaskLane(
			true,
			Runnable::run,
			answerId -> { }
		);

		assertThatThrownBy(() -> lane.submit(0L))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("answerId must be positive");
	}

	private static void await(CountDownLatch latch) {
		try {
			if (!latch.await(5, TimeUnit.SECONDS)) {
				throw new AssertionError("timed out waiting for test latch");
			}
		}
		catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new AssertionError("worker interrupted", exception);
		}
	}
}
