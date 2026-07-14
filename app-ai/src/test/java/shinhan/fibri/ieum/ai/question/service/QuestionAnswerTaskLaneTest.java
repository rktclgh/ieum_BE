package shinhan.fibri.ieum.ai.question.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import shinhan.fibri.ieum.ai.config.QuestionAnswerDispatchConfiguration;

class QuestionAnswerTaskLaneTest {

	@Test
	void usesOneWorkerThirtyTwoQueueSlotsAbortPolicyAndNeverRunsRejectedWorkOnCaller() throws Exception {
		ThreadPoolTaskExecutor executor = new QuestionAnswerDispatchConfiguration().questionAnswerTaskExecutor();
		CountDownLatch firstStarted = new CountDownLatch(1);
		CountDownLatch releaseFirst = new CountDownLatch(1);
		CountDownLatch processedQueued = new CountDownLatch(32);
		List<Long> processed = new CopyOnWriteArrayList<>();
		AtomicReference<String> firstWorkerThread = new AtomicReference<>();
		QuestionAnswerTaskLane lane = new QuestionAnswerTaskLane(true, executor, questionId -> {
			processed.add(questionId);
			if (questionId == 1L) {
				firstWorkerThread.set(Thread.currentThread().getName());
				firstStarted.countDown();
				try {
					releaseFirst.await(5, TimeUnit.SECONDS);
				}
				catch (InterruptedException exception) {
					Thread.currentThread().interrupt();
					throw new AssertionError("worker interrupted", exception);
				}
			}
			else {
				processedQueued.countDown();
			}
		});

		try {
			assertThat(lane.submit(1L)).isEqualTo(QuestionAnswerTaskSubmission.ENQUEUED);
			assertThat(firstStarted.await(5, TimeUnit.SECONDS)).isTrue();
			for (long questionId = 2; questionId <= 33; questionId++) {
				assertThat(lane.submit(questionId)).isEqualTo(QuestionAnswerTaskSubmission.ENQUEUED);
			}

			assertThat(lane.submit(2L)).isEqualTo(QuestionAnswerTaskSubmission.ALREADY_ACTIVE);
			assertThat(lane.submit(34L)).isEqualTo(QuestionAnswerTaskSubmission.SATURATED);
			assertThat(processed).doesNotContain(34L);
			assertThat(firstWorkerThread.get()).startsWith("ieum-question-answer-");
			assertThat(firstWorkerThread.get()).isNotEqualTo(Thread.currentThread().getName());
		}
		finally {
			releaseFirst.countDown();
			assertThat(processedQueued.await(5, TimeUnit.SECONDS)).isTrue();
			executor.shutdown();
		}
	}

	@Test
	void disabledLaneDoesNotSubmitWork() {
		List<Long> processed = new CopyOnWriteArrayList<>();
		QuestionAnswerTaskLane lane = new QuestionAnswerTaskLane(false, Runnable::run, processed::add);

		assertThat(lane.submit(42L)).isEqualTo(QuestionAnswerTaskSubmission.DISABLED);
		assertThat(lane.isEnabled()).isFalse();
		assertThat(processed).isEmpty();
	}

	@Test
	void removesTheActiveQuestionIdAndRethrowsUnexpectedExecutorFailures() {
		AtomicInteger submissions = new AtomicInteger();
		QuestionAnswerTaskLane lane = new QuestionAnswerTaskLane(true, command -> {
			submissions.incrementAndGet();
			throw new IllegalStateException("executor misconfigured");
		}, questionId -> { });

		assertThatThrownBy(() -> lane.submit(42L))
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("executor misconfigured");
		assertThatThrownBy(() -> lane.submit(42L))
			.isInstanceOf(IllegalStateException.class);
		assertThat(submissions).hasValue(2);
	}
}
