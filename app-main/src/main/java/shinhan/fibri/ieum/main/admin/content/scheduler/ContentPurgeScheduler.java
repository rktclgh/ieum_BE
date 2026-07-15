package shinhan.fibri.ieum.main.admin.content.scheduler;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import shinhan.fibri.ieum.main.admin.content.service.ContentPurgeService;

@Component
@RequiredArgsConstructor
public class ContentPurgeScheduler {

	private static final Logger log = LoggerFactory.getLogger(ContentPurgeScheduler.class);

	private final ContentPurgeService contentPurgeService;

	@Scheduled(cron = "0 0 4 * * *", zone = "Asia/Seoul")
	public void purgeExpiredQuestionContent() {
		int purged = contentPurgeService.purgeExpiredQuestionContent();
		log.info("Scheduled content purge completed. purgedQuestions={}", purged);
	}
}
