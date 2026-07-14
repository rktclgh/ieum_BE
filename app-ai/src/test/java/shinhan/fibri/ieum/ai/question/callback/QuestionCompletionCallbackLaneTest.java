package shinhan.fibri.ieum.ai.question.callback;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

class QuestionCompletionCallbackLaneTest {

	@Test
	void usesOneWorkerThirtyTwoQueueSlotsAbortPolicyAndNeverRunsRejectedWorkOnCaller() throws Exception {
		ThreadPoolTaskExecutor executor = QuestionCompletionCallbackConfiguration.callbackExecutor();
		CountDownLatch firstStarted = new CountDownLatch(1);
		CountDownLatch releaseFirst = new CountDownLatch(1);
		CountDownLatch queuedProcessed = new CountDownLatch(32);
		List<Long> delivered = new CopyOnWriteArrayList<>();
		AtomicReference<String> workerThread = new AtomicReference<>();
		QuestionCompletionCallbackLane lane = new QuestionCompletionCallbackLane(executor, questionId -> {
			delivered.add(questionId);
			if (questionId == 1L) {
				workerThread.set(Thread.currentThread().getName());
				firstStarted.countDown();
				try {
					releaseFirst.await(5, TimeUnit.SECONDS);
				}
				catch (InterruptedException exception) {
					Thread.currentThread().interrupt();
					throw new AssertionError(exception);
				}
			}
			else {
				queuedProcessed.countDown();
			}
		});

		try {
			assertThat(lane.submit(1L)).isEqualTo(CallbackSubmission.ENQUEUED);
			assertThat(firstStarted.await(5, TimeUnit.SECONDS)).isTrue();
			for (long questionId = 2L; questionId <= 33L; questionId++) {
				assertThat(lane.submit(questionId)).isEqualTo(CallbackSubmission.ENQUEUED);
			}

			assertThat(lane.submit(2L)).isEqualTo(CallbackSubmission.ALREADY_ACTIVE);
			assertThat(lane.submit(34L)).isEqualTo(CallbackSubmission.SATURATED);
			assertThat(delivered).doesNotContain(34L);
			assertThat(workerThread.get()).startsWith("ieum-question-callback-");
			assertThat(workerThread.get()).isNotEqualTo(Thread.currentThread().getName());
			assertThat(executor.getThreadPoolExecutor().getRejectedExecutionHandler())
				.isInstanceOf(ThreadPoolExecutor.AbortPolicy.class);
		}
		finally {
			releaseFirst.countDown();
			assertThat(queuedProcessed.await(5, TimeUnit.SECONDS)).isTrue();
			executor.shutdown();
		}
	}
}
