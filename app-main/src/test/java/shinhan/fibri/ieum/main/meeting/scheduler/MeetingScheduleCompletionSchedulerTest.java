package shinhan.fibri.ieum.main.meeting.scheduler;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import shinhan.fibri.ieum.main.meeting.service.MeetingScheduleMaintenanceService;

class MeetingScheduleCompletionSchedulerTest {

	private final MeetingScheduleMaintenanceService service = mock(MeetingScheduleMaintenanceService.class);
	private final MeetingScheduleCompletionScheduler scheduler = new MeetingScheduleCompletionScheduler(service);

	@Test
	void completeExpiredSchedulesRunsMaintenanceService() {
		scheduler.completeExpiredSchedules();

		verify(service).completeExpiredSchedules(any(OffsetDateTime.class));
	}
}
