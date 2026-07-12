package shinhan.fibri.ieum.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.ai.question.discovery")
public record QuestionTaskDiscoveryProperties(int batchSize) {

	public QuestionTaskDiscoveryProperties {
		if (batchSize < 1) {
			throw new IllegalArgumentException("batchSize must be positive");
		}
	}
}
