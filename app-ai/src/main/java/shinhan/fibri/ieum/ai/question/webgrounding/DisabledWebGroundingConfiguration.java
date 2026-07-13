package shinhan.fibri.ieum.ai.question.webgrounding;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
	prefix = "app.ai.features",
	name = "question-answer-enabled",
	havingValue = "true"
)
@ConditionalOnProperty(
	prefix = "app.ai.features",
	name = "web-grounding-enabled",
	havingValue = "false",
	matchIfMissing = true
)
public class DisabledWebGroundingConfiguration {

	@Bean
	DisabledWebGroundingGateway disabledWebGroundingGateway() {
		return new DisabledWebGroundingGateway();
	}
}
