package shinhan.fibri.ieum.main.admin.user.scheduler;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import shinhan.fibri.ieum.main.admin.user.repository.ExpiredSanctionRef;
import shinhan.fibri.ieum.main.admin.user.repository.UserSanctionRepository;
import shinhan.fibri.ieum.main.admin.user.service.AdminSanctionService;

class SanctionExpirySchedulerTest {

	private final UserSanctionRepository sanctionRepository = mock(UserSanctionRepository.class);
	private final AdminSanctionService sanctionService = mock(AdminSanctionService.class);
	private final SanctionExpiryScheduler scheduler = new SanctionExpiryScheduler(sanctionRepository, sanctionService);

	@Test
	void releasesEachExpiredSanctionWithItsUserId() {
		when(sanctionRepository.findExpiredTemporarySanctions(org.mockito.ArgumentMatchers.any(OffsetDateTime.class)))
			.thenReturn(List.of(new ExpiredSanctionRef(1L, 10L), new ExpiredSanctionRef(2L, 20L)));

		scheduler.releaseExpiredTemporarySanctions();

		verify(sanctionService).releaseExpiredSanction(1L, 10L);
		verify(sanctionService).releaseExpiredSanction(2L, 20L);
	}

	@Test
	void oneFailureDoesNotStopProcessingTheRest() {
		when(sanctionRepository.findExpiredTemporarySanctions(org.mockito.ArgumentMatchers.any(OffsetDateTime.class)))
			.thenReturn(List.of(new ExpiredSanctionRef(1L, 10L), new ExpiredSanctionRef(2L, 20L)));
		doThrow(new RuntimeException("boom")).when(sanctionService).releaseExpiredSanction(1L, 10L);

		scheduler.releaseExpiredTemporarySanctions();

		verify(sanctionService).releaseExpiredSanction(2L, 20L);
	}

	@Test
	void noopWhenNothingExpired() {
		when(sanctionRepository.findExpiredTemporarySanctions(org.mockito.ArgumentMatchers.any(OffsetDateTime.class)))
			.thenReturn(List.of());

		scheduler.releaseExpiredTemporarySanctions();

		verify(sanctionService, never()).releaseExpiredSanction(anyLong(), anyLong());
	}
}
