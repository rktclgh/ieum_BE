package shinhan.fibri.ieum.ai.question.scheduler;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import shinhan.fibri.ieum.ai.config.QuestionTaskDiscoveryProperties;
import shinhan.fibri.ieum.ai.question.repository.QuestionTaskDiscoveryRepository;

class QuestionTaskDiscoverySchedulerTest {

	private final QuestionTaskDiscoveryRepository repository = mock(QuestionTaskDiscoveryRepository.class);
	private final QuestionTaskDiscoveryScheduler scheduler = new QuestionTaskDiscoveryScheduler(
		repository,
		new QuestionTaskDiscoveryProperties(20)
	);

	@Test
	void discoversTheConfiguredBatchOnEachTick() {
		scheduler.discoverTasks();

		verify(repository).discover(20);
	}

	@Test
	void isolatesRepositoryFailureSoTheNextTickCanRun() {
		doThrow(new IllegalStateException("database unavailable")).when(repository).discover(20);

		assertThatCode(scheduler::discoverTasks).doesNotThrowAnyException();
	}
}
