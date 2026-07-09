package shinhan.fibri.ieum.main.meeting.scheduler;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import shinhan.fibri.ieum.main.meeting.service.MeetingScheduleMaintenanceService;

@Component
@RequiredArgsConstructor
public class MeetingRecurrenceExpansionScheduler {

	private static final ZoneId SCHEDULE_ZONE = ZoneId.of("Asia/Seoul");

	private final MeetingScheduleMaintenanceService meetingScheduleMaintenanceService;

	@Scheduled(fixedDelay = 3_600_000)
	public void expandRecurringSchedules() {
		meetingScheduleMaintenanceService.expandRecurringSchedules(OffsetDateTime.now(SCHEDULE_ZONE));
	}
}
