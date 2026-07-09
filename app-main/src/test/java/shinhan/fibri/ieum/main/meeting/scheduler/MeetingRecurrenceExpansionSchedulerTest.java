package shinhan.fibri.ieum.main.meeting.scheduler;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import shinhan.fibri.ieum.main.meeting.service.MeetingScheduleMaintenanceService;

class MeetingRecurrenceExpansionSchedulerTest {

	private final MeetingScheduleMaintenanceService service = mock(MeetingScheduleMaintenanceService.class);
	private final MeetingRecurrenceExpansionScheduler scheduler = new MeetingRecurrenceExpansionScheduler(service);

	@Test
	void expandRecurringSchedulesRunsMaintenanceService() {
		scheduler.expandRecurringSchedules();

		verify(service).expandRecurringSchedules(any(OffsetDateTime.class));
	}
}
