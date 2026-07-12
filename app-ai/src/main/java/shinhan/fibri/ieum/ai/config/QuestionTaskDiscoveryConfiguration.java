package shinhan.fibri.ieum.ai.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import shinhan.fibri.ieum.ai.question.repository.QuestionTaskDiscoveryRepository;
import shinhan.fibri.ieum.ai.question.scheduler.QuestionTaskDiscoveryScheduler;

@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "app.ai.features", name = "question-discovery-enabled", havingValue = "true")
@EnableConfigurationProperties(QuestionTaskDiscoveryProperties.class)
public class QuestionTaskDiscoveryConfiguration {

	@Bean
	QuestionTaskDiscoveryScheduler questionTaskDiscoveryScheduler(
		QuestionTaskDiscoveryRepository repository,
		QuestionTaskDiscoveryProperties properties
	) {
		return new QuestionTaskDiscoveryScheduler(repository, properties);
	}
}
