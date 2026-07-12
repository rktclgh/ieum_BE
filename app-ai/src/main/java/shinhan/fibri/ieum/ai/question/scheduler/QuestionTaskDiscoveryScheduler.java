package shinhan.fibri.ieum.ai.question.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import shinhan.fibri.ieum.ai.config.QuestionTaskDiscoveryProperties;
import shinhan.fibri.ieum.ai.question.repository.QuestionTaskDiscoveryRepository;

public class QuestionTaskDiscoveryScheduler {

	private static final Logger log = LoggerFactory.getLogger(QuestionTaskDiscoveryScheduler.class);

	private final QuestionTaskDiscoveryRepository repository;
	private final QuestionTaskDiscoveryProperties properties;

	public QuestionTaskDiscoveryScheduler(
		QuestionTaskDiscoveryRepository repository,
		QuestionTaskDiscoveryProperties properties
	) {
		this.repository = repository;
		this.properties = properties;
	}

	@Scheduled(fixedDelayString = "${app.ai.question.discovery.fixed-delay:5s}")
	public void discoverTasks() {
		try {
			repository.discover(properties.batchSize());
		} catch (RuntimeException exception) {
			log.error("Question AI task discovery tick failed", exception);
		}
	}
}
