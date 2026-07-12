package shinhan.fibri.ieum.ai.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import shinhan.fibri.ieum.ai.question.repository.QuestionTaskDiscoveryRepository;
import shinhan.fibri.ieum.ai.question.scheduler.QuestionTaskDiscoveryScheduler;

class QuestionTaskDiscoveryConfigurationTest {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
		.withUserConfiguration(QuestionTaskDiscoveryConfiguration.class, Dependencies.class)
		.withPropertyValues("app.ai.question.discovery.batch-size=20");

	@Test
	void doesNotRegisterTheSchedulerWhenTheFeatureIsDisabled() {
		contextRunner.run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context).doesNotHaveBean(QuestionTaskDiscoveryScheduler.class);
		});
	}

	@Test
	void registersTheSchedulerWhenTheFeatureIsEnabled() {
		contextRunner
			.withPropertyValues("app.ai.features.question-discovery-enabled=true")
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context).hasSingleBean(QuestionTaskDiscoveryScheduler.class);
			});
	}

	@Configuration
	static class Dependencies {

		@Bean
		QuestionTaskDiscoveryRepository questionTaskDiscoveryRepository() {
			return mock(QuestionTaskDiscoveryRepository.class);
		}
	}
}
