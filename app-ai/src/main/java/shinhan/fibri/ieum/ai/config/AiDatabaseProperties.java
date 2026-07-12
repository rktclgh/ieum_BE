package shinhan.fibri.ieum.ai.config;

import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.ai.database")
public record AiDatabaseProperties(int embeddingDimensions, Set<String> requiredExtensions) {

	public AiDatabaseProperties {
		if (embeddingDimensions != 768) {
			throw new IllegalArgumentException("embeddingDimensions must be 768");
		}
		requiredExtensions = Set.copyOf(requiredExtensions);
	}
}
