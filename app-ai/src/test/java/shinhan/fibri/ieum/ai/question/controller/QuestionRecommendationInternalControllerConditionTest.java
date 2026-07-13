package shinhan.fibri.ieum.ai.question.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import shinhan.fibri.ieum.ai.question.service.QuestionRecommendationService;

class QuestionRecommendationInternalControllerConditionTest {

	private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
		.withUserConfiguration(TestConfiguration.class);

	@Test
	void doesNotRegisterTheControllerWhenRecommendationsAreDisabledByDefault() {
		contextRunner.run(context ->
			assertThat(context).doesNotHaveBean(QuestionRecommendationInternalController.class)
		);
	}

	@Test
	void registersTheControllerOnlyWhenRecommendationsAreEnabled() {
		contextRunner
			.withPropertyValues("app.ai.features.question-recommendations-enabled=true")
			.run(context -> assertThat(context).hasSingleBean(QuestionRecommendationInternalController.class));
	}

	@Configuration(proxyBeanMethods = false)
	@Import(QuestionRecommendationInternalController.class)
	static class TestConfiguration {

		@Bean
		QuestionRecommendationService questionRecommendationService() {
			return mock(QuestionRecommendationService.class);
		}
	}
}
