package shinhan.fibri.ieum;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.bootstrap.ConfigurableBootstrapContext;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.mock.env.MockEnvironment;
import shinhan.fibri.ieum.ai.knowledge.packageimport.KnowledgeImportApplicationConfiguration;

class AiApplicationModeTest {

	@Test
	void defaultsToServerMode() {
		assertThat(AiApplicationMode.from(null)).isEqualTo(AiApplicationMode.SERVER);
		assertThat(AiApplication.application().getListeners())
			.anyMatch(AiApplicationModeEnvironmentListener.class::isInstance);
	}

	@Test
	void acceptsOnlyCanonicalModes() {
		assertThat(AiApplicationMode.from("server")).isEqualTo(AiApplicationMode.SERVER);
		assertThat(AiApplicationMode.from("knowledge-import"))
			.isEqualTo(AiApplicationMode.KNOWLEDGE_IMPORT);

		assertThatThrownBy(() -> AiApplicationMode.from(""))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("app.ai.mode");
		assertThatThrownBy(() -> AiApplicationMode.from(" knowledge-import "))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("knowledge-import");
		assertThatThrownBy(() -> AiApplicationMode.from("worker"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("worker");
	}

	@Test
	void routesServerModeToExistingServletApplication() {
		SpringApplication application = AiApplication.application();
		publishEnvironment(application, new MockEnvironment().withProperty("app.ai.mode", "server"));

		assertThat(application.getWebApplicationType()).isEqualTo(WebApplicationType.SERVLET);
		assertThat(application.getAllSources()).contains(AiApplication.class);
		assertThat(application.getAllSources()).doesNotContain(KnowledgeImportApplicationConfiguration.class);
	}

	@Test
	void routesKnowledgeImportModeToIsolatedNonWebApplication() {
		SpringApplication application = AiApplication.application();
		MockEnvironment environment = new MockEnvironment()
			.withProperty("app.ai.mode", "knowledge-import")
			.withProperty("spring.main.web-application-type", "servlet");
		publishEnvironment(application, environment);

		assertThat(application.getWebApplicationType()).isEqualTo(WebApplicationType.NONE);
		assertThat(environment.getProperty("spring.main.web-application-type")).isEqualTo("none");
		assertThat(application.getAllSources()).contains(KnowledgeImportApplicationConfiguration.class);
		assertThat(application.getAllSources()).doesNotContain(AiApplication.class);
	}

	@Test
	void closesOnlyOneShotImportContext() {
		ConfigurableApplicationContext serverContext = context("server");
		ConfigurableApplicationContext importContext = context("knowledge-import");

		AiApplication.closeIfOneShot(serverContext);
		AiApplication.closeIfOneShot(importContext);

		verify(serverContext, never()).close();
		verify(importContext).close();
	}

	private void publishEnvironment(SpringApplication application, ConfigurableEnvironment environment) {
		new AiApplicationModeEnvironmentListener().onApplicationEvent(
			new ApplicationEnvironmentPreparedEvent(
				mock(ConfigurableBootstrapContext.class),
				application,
				new String[0],
				environment
			)
		);
	}

	private ConfigurableApplicationContext context(String mode) {
		ConfigurableApplicationContext context = mock(ConfigurableApplicationContext.class);
		ConfigurableEnvironment environment = new MockEnvironment()
			.withProperty("app.ai.mode", mode);
		when(context.getEnvironment()).thenReturn(environment);
		return context;
	}
}
