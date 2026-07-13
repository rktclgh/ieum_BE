package shinhan.fibri.ieum.ai.question.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "app.ai.features", name = "question-answer-enabled", havingValue = "true")
@EnableConfigurationProperties(QuestionAnalyzerProperties.class)
public class QuestionAnalyzerConfiguration {

	@Bean
	QuestionQueryAnalyzer questionQueryAnalyzer(
		ChatModel chatModel,
		ObjectMapper objectMapper,
		QuestionAnalyzerProperties properties,
		@Value("${spring.ai.bedrock.aws.region:}") String effectiveBedrockRegion,
		@Value("${spring.ai.bedrock.aws.timeout:}") String effectiveBedrockTimeout
	) {
		validateEffectiveTransport(properties, effectiveBedrockRegion, effectiveBedrockTimeout);
		return new BedrockNovaQuestionQueryAnalyzer(chatModel, objectMapper, properties);
	}

	private void validateEffectiveTransport(
		QuestionAnalyzerProperties properties,
		String effectiveRegion,
		String effectiveTimeout
	) {
		String region = effectiveRegion == null ? "" : effectiveRegion.trim();
		if (!properties.bedrockRegion().equals(region)) {
			throw new IllegalArgumentException("Effective Bedrock region violates the question analyzer contract");
		}
		Duration timeout;
		try {
			timeout = DurationStyle.detectAndParse(effectiveTimeout);
		}
		catch (RuntimeException exception) {
			throw new IllegalArgumentException("Invalid effective Bedrock timeout", exception);
		}
		if (!properties.modelTimeout().equals(timeout)) {
			throw new IllegalArgumentException("Effective Bedrock timeout violates the question analyzer contract");
		}
	}
}
