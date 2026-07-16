package shinhan.fibri.ieum.ai.question.embedding;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "app.ai.question-answer.embedding")
public record QuestionEmbeddingProperties(
	@DefaultValue("10s") Duration modelTimeout,
	@DefaultValue("1") int totalAttempts
) {

	static final Duration REQUIRED_MODEL_TIMEOUT = Duration.ofSeconds(10);
	static final int REQUIRED_TOTAL_ATTEMPTS = 1;

	public QuestionEmbeddingProperties {
		if (!REQUIRED_MODEL_TIMEOUT.equals(modelTimeout)) {
			throw new IllegalArgumentException("Embedding model timeout must be exactly 10 seconds");
		}
		if (totalAttempts != REQUIRED_TOTAL_ATTEMPTS) {
			throw new IllegalArgumentException("Embedding transport attempts must be exactly 1");
		}
	}

}
