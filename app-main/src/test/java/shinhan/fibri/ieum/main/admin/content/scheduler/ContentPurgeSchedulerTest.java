package shinhan.fibri.ieum.main.admin.content.scheduler;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import shinhan.fibri.ieum.main.admin.content.service.ContentPurgeService;

class ContentPurgeSchedulerTest {

	private final ContentPurgeService service = mock(ContentPurgeService.class);
	private final ContentPurgeScheduler scheduler = new ContentPurgeScheduler(service);

	@Test
	void scheduledRunDelegatesToPurgeService() {
		scheduler.purgeExpiredQuestionContent();

		verify(service).purgeExpiredQuestionContent();
	}
}
