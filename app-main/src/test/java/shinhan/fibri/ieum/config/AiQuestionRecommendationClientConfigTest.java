package shinhan.fibri.ieum.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import shinhan.fibri.ieum.common.ai.question.dto.InternalQuestionRecommendationRequest;
import shinhan.fibri.ieum.main.ai.client.AiQuestionRecommendationClient;
import shinhan.fibri.ieum.main.ai.client.AiQuestionRecommendationClientException;

class AiQuestionRecommendationClientConfigTest {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withUserConfiguration(AiQuestionRecommendationClientConfig.class)
		.withBean(ObjectMapper.class, ObjectMapper::new);

	@Test
	void doesNotCreateClientWhenQuestionRecommendationsAreDisabled() {
		contextRunner.run(context -> assertThat(context).doesNotHaveBean(AiQuestionRecommendationClient.class));
	}

	@Test
	void createsClientOnlyWhenQuestionRecommendationsAreEnabled() {
		contextRunner
			.withPropertyValues(
				"app.ai.question-recommendations.enabled=true",
				"app.ai.question-recommendations.base-url=http://10.0.20.15:8080",
				"app.ai.question-recommendations.allowed-hosts=10.0.20.15"
			)
			.run(context -> {
				assertThat(context).hasSingleBean(QuestionRecommendationClientProperties.class);
				assertThat(context).hasSingleBean(AiQuestionRecommendationClient.class);
				assertThat(context.getBean(QuestionRecommendationClientProperties.class).readTimeout())
					.isEqualTo(Duration.ofSeconds(12));
			});
	}

	@Test
	void doesNotFollowRedirectResponsesFromTheAiService() throws Exception {
		AtomicInteger recommendationRequests = new AtomicInteger();
		AtomicInteger redirectedRequests = new AtomicInteger();
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/ai/v1/internal/questions/recommendations", exchange -> {
			recommendationRequests.incrementAndGet();
			exchange.getResponseHeaders().add("Location", "/redirect-target");
			exchange.sendResponseHeaders(302, -1);
			exchange.close();
		});
		server.createContext("/redirect-target", exchange -> {
			redirectedRequests.incrementAndGet();
			exchange.sendResponseHeaders(204, -1);
			exchange.close();
		});
		server.start();

		try {
			AiQuestionRecommendationClient client = new AiQuestionRecommendationClientConfig()
				.aiQuestionRecommendationClient(
					new QuestionRecommendationClientProperties(
						"http://127.0.0.1:" + server.getAddress().getPort(),
						"127.0.0.1",
						Duration.ofSeconds(2),
						Duration.ofSeconds(12)
					),
					new ObjectMapper()
				);

			try {
				client.recommend(new InternalQuestionRecommendationRequest("title", "content", null, 3));
			}
			catch (AiQuestionRecommendationClientException ignored) {
			}
		}
		finally {
			server.stop(0);
		}

		assertThat(recommendationRequests).hasValue(1);
		assertThat(redirectedRequests).hasValue(0);
	}
}
