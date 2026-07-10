package shinhan.fibri.ieum.main.admin.user.scheduler;

import java.time.OffsetDateTime;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import shinhan.fibri.ieum.main.admin.user.repository.UserSanctionRepository;
import shinhan.fibri.ieum.main.admin.user.service.AdminSanctionService;

@Component
@RequiredArgsConstructor
public class SanctionExpiryScheduler {

	private static final Logger log = LoggerFactory.getLogger(SanctionExpiryScheduler.class);

	private final UserSanctionRepository userSanctionRepository;
	private final AdminSanctionService adminSanctionService;

	@Scheduled(fixedDelay = 60_000)
	public void releaseExpiredTemporarySanctions() {
		for (Long sanctionId : userSanctionRepository.findExpiredTemporaryActiveIds(OffsetDateTime.now())) {
			try {
				adminSanctionService.releaseExpiredSanction(sanctionId);
			} catch (RuntimeException exception) {
				log.error("Failed to release expired sanction: sanctionId={}", sanctionId, exception);
			}
		}
	}
}
