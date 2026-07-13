package shinhan.fibri.ieum;

import java.util.List;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.Ordered;
import org.springframework.core.env.MapPropertySource;

final class AiApplicationModeEnvironmentListener
	implements ApplicationListener<ApplicationEnvironmentPreparedEvent>, Ordered {

	private static final String MODE_INVARIANTS_PROPERTY_SOURCE = "appAiModeInvariants";

	@Override
	public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
		AiApplicationMode mode = AiApplicationMode.from(
			event.getEnvironment().getProperty("app.ai.mode")
		);
		SpringApplication application = event.getSpringApplication();
		event.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
			MODE_INVARIANTS_PROPERTY_SOURCE,
			Map.of("spring.main.web-application-type", mode.webApplicationTypePropertyValue())
		));
		application.addPrimarySources(List.of(mode.applicationSource()));
		application.setWebApplicationType(mode.webApplicationType());
	}

	@Override
	public int getOrder() {
		return Ordered.LOWEST_PRECEDENCE;
	}
}
